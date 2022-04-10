package io.stargate.sgv2.dynamosvc.dynamo;

import static com.amazonaws.services.dynamodbv2.model.KeyType.HASH;
import static com.amazonaws.services.dynamodbv2.model.KeyType.RANGE;

import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.google.common.base.Preconditions;
import io.stargate.bridge.proto.QueryOuterClass;
import io.stargate.sgv2.dynamosvc.grpc.BridgeProtoValueConverters;
import io.stargate.sgv2.dynamosvc.grpc.FromProtoConverter;
import io.stargate.sgv2.dynamosvc.models.PrimaryKey;
import io.stargate.sgv2.dynamosvc.parser.ProjectionExpressionVisitor;
import io.stargate.sgv2.dynamosvc.parser.ProjectionLexer;
import io.stargate.sgv2.dynamosvc.parser.ProjectionParser;
import java.util.*;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Proxy {
  private static final Logger logger = LoggerFactory.getLogger(Proxy.class);

  public static final String KEYSPACE_NAME = "dynamodb";
  public static final ObjectMapper awsRequestMapper =
      new ObjectMapper()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .setSerializationInclusion(JsonInclude.Include.NON_NULL)
          .setPropertyNamingStrategy(
              // Map to AWS api style
              new PropertyNamingStrategy.UpperCamelCaseStrategy() {
                @Override
                public String translate(String input) {
                  String output = super.translate(input);

                  if (output != null && output.length() >= 2) {
                    switch (output) {
                      case "Ss":
                        return "SS";
                      case "Bool":
                        return "BOOL";
                      case "Ns":
                        return "NS";
                      default:
                        break;
                    }
                  }

                  return output;
                }
              });

  protected PrimaryKey getPrimaryKey(List<KeySchemaElement> keySchema) {
    PrimaryKey primaryKey = new PrimaryKey();
    for (KeySchemaElement keySchemaElement : keySchema) {
      String type = keySchemaElement.getKeyType();
      String name = keySchemaElement.getAttributeName();
      if (type.equals(HASH.toString())) {
        primaryKey.setPartitionKey(name);
      } else if (type.equals(RANGE.toString())) {
        primaryKey.setClusteringKey(name);
      }
    }
    return primaryKey;
  }

  protected Collection<Map<String, AttributeValue>> collectResults(
      QueryOuterClass.ResultSet rs, String projectionQuery, Map<String, String> attrNames) {
    // retrieve retained columns and extract projection values
    List<Map<String, AttributeValue>> rawResults = convertRows(rs);
    if (StringUtils.isBlank(projectionQuery)) {
      return rawResults;
    }
    List<Map<String, AttributeValue>> filteredResults = new ArrayList<>(rawResults.size());
    for (int i = 0; i < rawResults.size(); i++) {
      filteredResults.add(new HashMap<>());
    }
    for (String projection : projectionQuery.split(",")) {
      CharStream chars = CharStreams.fromString(projection.trim());
      Lexer lexer = new ProjectionLexer(chars);
      CommonTokenStream tokens = new CommonTokenStream(lexer);
      ProjectionParser parser = new ProjectionParser(tokens);
      for (int i = 0; i < rawResults.size(); i++) {
        ProjectionExpressionVisitor visitor =
            new ProjectionExpressionVisitor(
                attrNames, new AttributeValue().withM(rawResults.get(i)));
        ParseTree tree = parser.start();
        // TODO: what should be the key name?
        filteredResults.get(i).put(projection.trim(), visitor.find(tree));
      }
    }
    return filteredResults;
  }

  private AttributeValue extractProjectionValue(
      AttributeValue fullValue, List<String> paths, Map<String, String> attrNames) {
    if (CollectionUtils.isEmpty(paths)) {
      return fullValue;
    }
    AttributeValue projectedValue = null;
    for (String path : paths) {
      AttributeValue subValue = extractProjectionValue(fullValue, path, attrNames);
      if (projectedValue == null) {
        projectedValue = subValue;
      } else {
        mergeAttributeValue(projectedValue, subValue);
      }
    }
    purgeListPlaceHolders(projectedValue);
    return projectedValue;
  }

  private void purgeListPlaceHolders(AttributeValue value) {
    if (value == null) return;
    if (value.getL() != null) {
      List<AttributeValue> newList = new ArrayList<>();
      for (AttributeValue v : value.getL()) {
        if (v != null) {
          purgeListPlaceHolders(v);
          newList.add(v);
        }
      }
      value.setL(newList);
    } else if (value.getM() != null) {
      for (AttributeValue v : value.getM().values()) {
        purgeListPlaceHolders(v);
      }
    }
  }

  private void mergeAttributeValue(AttributeValue fullValue, AttributeValue subValue) {
    if (subValue.getM() != null) {
      Preconditions.checkArgument(fullValue.getM() != null);
      Preconditions.checkArgument(subValue.getM().size() == 1);
      String key = subValue.getM().keySet().iterator().next();
      if (fullValue.getM().containsKey(key)) {
        mergeAttributeValue(fullValue.getM().get(key), subValue.getM().get(key));
      } else {
        fullValue.getM().put(key, subValue.getM().get(key));
      }
    } else {
      Preconditions.checkArgument(fullValue.getL() != null && subValue.getL() != null);
      Preconditions.checkArgument(fullValue.getL().size() == subValue.getL().size());
      for (int i = 0, end = subValue.getL().size(); i < end; i++) {
        AttributeValue childValue = subValue.getL().get(i);
        if (childValue != null) {
          if (fullValue.getL().get(i) != null) {
            mergeAttributeValue(fullValue.getL().get(i), childValue);
          } else {
            fullValue.getL().set(i, childValue);
          }
        }
      }
    }
  }

  private AttributeValue extractProjectionValue(
      AttributeValue fullValue, String path, Map<String, String> attrNames) {
    Preconditions.checkArgument(fullValue != null, "AttributeValue cannot be null");
    if (StringUtils.isEmpty(path)) {
      return fullValue;
    }
    if (path.charAt(0) == '.') {
      // map structure
      Preconditions.checkArgument(
          fullValue.getM() != null, "AttributeValue is not a valid map: " + fullValue);
      int i = 1;
      while (i < path.length() && path.charAt(i) != '.' && path.charAt(i) != '[') {
        i++;
      }
      String attr = getKeyName(path.substring(1, i), attrNames);
      AttributeValue childValue =
          extractProjectionValue(fullValue.getM().get(attr), path.substring(i), attrNames);
      AttributeValue newValue = new AttributeValue();
      newValue.setM(new HashMap<>(Collections.singletonMap(attr, childValue)));
      return newValue;
    } else {
      // array structure
      Preconditions.checkArgument(path.charAt(0) == '[', "Invalid projection expression: " + path);
      int num = 0;
      int i = 1;
      while (i < path.length() && path.charAt(i) != ']') {
        char ch = path.charAt(i);
        Preconditions.checkArgument(ch >= '0' && ch <= '9', "Array offset must be number: " + path);
        num *= 10;
        num += path.charAt(i) - '0';
        i++;
      }
      Preconditions.checkArgument(
          path.charAt(i) == ']', "Invalid array offset expression: " + path);
      List<AttributeValue> list = fullValue.getL();
      if (CollectionUtils.isNotEmpty(list)) {
        AttributeValue childValue =
            extractProjectionValue(list.get(num), path.substring(i + 1), attrNames);
        // we put the extracted value in its original position, and set
        // other positions of the array to null, so that we don't break
        // the relative positions needed in the merge step
        AttributeValue newValue = new AttributeValue();
        List<AttributeValue> newList = new ArrayList<>(list.size());
        for (int k = 0, end = list.size(); k < end; k++) {
          newList.add(k == num ? childValue : null);
        }
        newValue.setL(newList);
        return newValue;
      }
    }
    logger.debug(
        "Invalid projection path {} for value {}, ignore by setting the value to null",
        path,
        fullValue);
    return null;
  }

  /**
   * Given a raw key name in expression, retrieve its real key name
   *
   * <p>A raw key name might be a key name or a key placeholder. A placeholder starts with a '#'
   * pound sign, whose actual value needs to be retrieved from attributeNames.
   *
   * <p>See more at
   * https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.ExpressionAttributeNames.html
   *
   * @param rawKeyName
   * @param attributeNames
   * @return
   */
  protected String getKeyName(String rawKeyName, Map<String, String> attributeNames) {
    if (rawKeyName.charAt(0) == '#') {
      return attributeNames.get(rawKeyName);
    } else {
      return rawKeyName;
    }
  }

  private List<Map<String, AttributeValue>> convertRows(QueryOuterClass.ResultSet rs) {
    FromProtoConverter converter =
        BridgeProtoValueConverters.instance().fromProtoConverter(rs.getColumnsList());
    List<Map<String, AttributeValue>> resultRows = new ArrayList<>();
    List<QueryOuterClass.Row> rows = rs.getRowsList();
    for (QueryOuterClass.Row row : rows) {
      resultRows.add(converter.mapFromProtoValues(row.getValuesList()));
    }
    return resultRows;
  }

  protected AttributeValue getExpressionAttributeValue(
      Map<String, AttributeValue> map, String key) {
    AttributeValue value = map.get(key);
    if (value == null) {
      throw new IllegalArgumentException(key + " does not appear in expression attributes");
    }
    return value;
  }
}
