package io.stargate.sgv2.dynamosvc.dynamo;

import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import io.stargate.sgv2.common.grpc.StargateBridgeClient;
import io.stargate.web.docsapi.dao.DocumentDB;

import java.io.IOException;
import java.util.Map;

public class ItemProxy {
  public PutItemResult putItem(PutItemRequest putItemRequest, StargateBridgeClient bridge)
      throws IOException {
    Map<String, AttributeValue> item = putItemRequest.getItem();
    DocumentDB documentDB; // TODO: retrieve it
    documentDB
    return new PutItemResult();
  }
}
