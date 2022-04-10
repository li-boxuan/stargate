package io.stargate.sgv2.dynamosvc.grpc;

import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import io.stargate.bridge.proto.QueryOuterClass;
import io.stargate.sgv2.dynamosvc.dynamo.DataMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converter that knows how to convert a single row from the "Bridge" Stargate Protobuf result into
 * a representation (currently {@link Map} with column name as key, Java value that can be
 * serialized by frontend framework (like DropWizard) as value, usually as JSON.
 */
public class FromProtoConverter {

  protected final String[] columnNames;
  protected final FromProtoValueCodec[] codecs;

  protected FromProtoConverter(String[] columnNames, FromProtoValueCodec[] codecs) {
    this.columnNames = columnNames;
    this.codecs = codecs;
  }

  /**
   * Factory method for constructing converter instances, given ordered sets of column names and
   * matching {@link FromProtoValueCodec}s.
   */
  public static FromProtoConverter construct(String[] columnNames, FromProtoValueCodec[] codecs) {
    return new FromProtoConverter(columnNames, codecs);
  }

  /** Method called to convert Bridge Protobuf values into DynamoDB object values */
  public Map<String, AttributeValue> mapFromProtoValues(List<QueryOuterClass.Value> values) {
    Map<String, AttributeValue> result = new LinkedHashMap<>();
    for (int i = 0, end = values.size(); i < end; ++i) {
      try {
        Object value = codecs[i].fromProtoValue(values.get(i));
        if (value != null) {
          result.put(columnNames[i], DataMapper.toDynamo(value));
        }
      } catch (Exception e) {
        throw new IllegalStateException(
            String.format(
                "Internal error: failed to convert value of column #%d/#%d ('%s'), problem: %s",
                i + 1, end, columnNames[i], e.getMessage()),
            e);
      }
    }
    return result;
  }
}
