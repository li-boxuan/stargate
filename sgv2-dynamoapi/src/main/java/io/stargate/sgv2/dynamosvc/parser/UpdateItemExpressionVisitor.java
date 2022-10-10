package io.stargate.sgv2.dynamosvc.parser;

import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import io.stargate.bridge.proto.QueryOuterClass.Value;
import io.stargate.sgv2.common.cql.builder.ValueModifier;
import io.stargate.sgv2.dynamosvc.dynamo.DataMapper;
import java.util.*;

public class UpdateItemExpressionVisitor extends UpdateExpressionBaseVisitor<Object> {
  private Map<String, String> nameMap;

  private Map<String, AttributeValue> valueMap;

  public UpdateItemExpressionVisitor(
      Map<String, String> nameMap, Map<String, AttributeValue> valueMap) {
    Objects.requireNonNull(nameMap);
    Objects.requireNonNull(valueMap);
    this.nameMap = nameMap;
    this.valueMap = valueMap;
  }

  @Override
  public List<ValueModifier> visitSingleStat(UpdateExpressionParser.SingleStatContext ctx) {
    return (List<ValueModifier>) this.visit(ctx.stat());
  }

  @Override
  public List<ValueModifier> visitSetStat(UpdateExpressionParser.SetStatContext ctx) {
    return (List<ValueModifier>) this.visit(ctx.setTerms());
  }

  @Override
  public List<ValueModifier> visitSingleSet(UpdateExpressionParser.SingleSetContext ctx) {
    return new ArrayList<>(Arrays.asList(this.visitSetTerm(ctx.setTerm())));
  }

  @Override
  public List<ValueModifier> visitMultiSet(UpdateExpressionParser.MultiSetContext ctx) {
    List<ValueModifier> modifiers = (List<ValueModifier>) this.visit(ctx.setTerms());
    modifiers.add(this.visitSetTerm(ctx.setTerm()));
    return modifiers;
  }

  @Override
  public ValueModifier visitSetTerm(UpdateExpressionParser.SetTermContext ctx) {
    String columnName = this.visitName(ctx.name());
    AttributeValue value = this.visitValue(ctx.value());
    return ValueModifier.set(columnName, (Value) DataMapper.fromDynamo(value));
  }

  //    @Override
  //    public Object visitSinglePair(UpdateExpressionParser.SinglePairContext ctx) {
  //        return super.visitSinglePair(ctx);
  //    }

  @Override
  public String visitName(UpdateExpressionParser.NameContext ctx) {
    System.out.println("Name: " + ctx.WORD().getText());
    return ctx.WORD().getText();
  }

  @Override
  public AttributeValue visitValue(UpdateExpressionParser.ValueContext ctx) {
    String placeholder = ctx.VALUE_PLACEHOLDER().getText();
    System.out.println("Value: " + placeholder);
    return valueMap.get(placeholder);
  }
}
