package io.stargate.sgv2.dynamosvc.parser;

import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;
import io.stargate.sgv2.common.cql.builder.ValueModifier;
import java.util.*;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;

public class UpdateItemExpressionVisitorTest {
  @Test
  public void testSimpleSet() {
    Map<String, String> nameMap = new HashMap<>();
    Map<String, AttributeValue> valueMap =
        new HashMap() {
          {
            put(":val1", new AttributeValue().withN("42"));
            put(":val2", new AttributeValue().withN("4.2"));
            put(":val3", new AttributeValue().withS("alice"));
          }
        };
    evaluateUpdateItemExpression("SET col1 = :val1", nameMap, valueMap);
    evaluateUpdateItemExpression("SET col1 = :val1, col2 = :val2, col3 = :val3", nameMap, valueMap);
  }

  @Test
  public void testUpdateSpec() {
    UpdateItemSpec updateItemSpec =
        new UpdateItemSpec()
            .withPrimaryKey("Name", "testName")
            .withUpdateExpression("SET Serial = :val1, Price = :val2, Model = :val3")
            .withValueMap(
                new ValueMap()
                    .withNumber(":val1", 45)
                    .withNumber(":val2", 20.0)
                    .withString(":val3", "B"));
    UpdateItemRequest updateItemRequest = updateItemSpec.getRequest();
    final String updateExpression = updateItemRequest.getUpdateExpression();
    CharStream chars = CharStreams.fromString(updateExpression);
    Lexer lexer = new UpdateExpressionLexer(chars);
    CommonTokenStream tokens = new CommonTokenStream(lexer);

    UpdateExpressionParser parser = new UpdateExpressionParser(tokens);

    UpdateItemExpressionVisitor visitor =
        new UpdateItemExpressionVisitor(
            updateItemRequest.getExpressionAttributeNames(),
            updateItemRequest.getExpressionAttributeValues());
    ParseTree tree = parser.expr();
    List<ValueModifier> modifiers = (List<ValueModifier>) visitor.visit(tree);

    for (ValueModifier valueModifier : modifiers) {
      System.out.printf(
          "Column name: %s, value: %s\n",
          valueModifier.target().columnName(), valueModifier.value().toString());
    }
  }

  void evaluateUpdateItemExpression(
      String expr, Map<String, String> nameMap, Map<String, AttributeValue> valueMap) {
    CharStream chars = CharStreams.fromString(expr);

    Lexer lexer = new UpdateExpressionLexer(chars);
    CommonTokenStream tokens = new CommonTokenStream(lexer);

    UpdateExpressionParser parser = new UpdateExpressionParser(tokens);

    UpdateItemExpressionVisitor visitor = new UpdateItemExpressionVisitor(nameMap, valueMap);
    ParseTree tree = parser.expr();
    List<ValueModifier> result = (List<ValueModifier>) visitor.visit(tree);
    for (ValueModifier valueModifier : result) {
      System.out.printf(
          "Column name: %s, value: %s\n",
          valueModifier.target().columnName(), valueModifier.value().toString());
    }
  }
}
