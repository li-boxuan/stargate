package io.stargate.it.http.dynamo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.amazonaws.services.dynamodbv2.model.*;
import io.stargate.it.driver.CqlSessionExtension;
import io.stargate.it.driver.CqlSessionSpec;
import io.stargate.it.http.ApiServiceExtension;
import io.stargate.it.http.ApiServiceSpec;
import net.jcip.annotations.NotThreadSafe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@NotThreadSafe
@ExtendWith(CqlSessionExtension.class)
@CqlSessionSpec()
@ExtendWith(ApiServiceExtension.class)
@ApiServiceSpec(parametersCustomizer = "buildApiServiceParameters")
public class DynamoApiTableTest extends BaseDynamoApiTest {

  @Test
  public void testDeleteNonExistentTable() {
    DeleteTableRequest req = new DeleteTableRequest().withTableName("non-existent");
    assertThrows(ResourceNotFoundException.class, () -> awsClient.deleteTable(req));
    // TODO: ensure the exceptions are the same
    assertThrows(Exception.class, () -> proxyClient.deleteTable(req));
  }

  @Test
  public void testCreateThenDeleteTable() {
    CreateTableRequest req =
        new CreateTableRequest()
            .withTableName("foo")
            .withProvisionedThroughput(
                new ProvisionedThroughput()
                    .withReadCapacityUnits(100L)
                    .withWriteCapacityUnits(100L))
            .withKeySchema(new KeySchemaElement("Name", KeyType.HASH))
            .withAttributeDefinitions(new AttributeDefinition("Name", ScalarAttributeType.S));

    awsClient.createTable(req);
    proxyClient.createTable(req);

    awsClient.deleteTable("foo");
    proxyClient.deleteTable("foo");
  }

  @Test
  public void testListTables() {
    CreateTableRequest req1 =
            new CreateTableRequest()
                    .withTableName("aaa")
                    .withProvisionedThroughput(
                            new ProvisionedThroughput()
                                    .withReadCapacityUnits(100L)
                                    .withWriteCapacityUnits(100L))
                    .withKeySchema(new KeySchemaElement("Name", KeyType.HASH))
                    .withAttributeDefinitions(new AttributeDefinition("Name", ScalarAttributeType.S));
    CreateTableRequest req2 = req1.clone().withTableName("bbb");
    CreateTableRequest req3 = req1.clone().withTableName("ccc");
    CreateTableRequest req4 = req1.clone().withTableName("ddd");

    awsClient.createTable(req1);
    awsClient.createTable(req2);
    awsClient.createTable(req3);
    awsClient.createTable(req4);
    proxyClient.createTable(req1);
    proxyClient.createTable(req2);
    proxyClient.createTable(req3);
    proxyClient.createTable(req4);

    ListTablesResult awsResult;
    ListTablesResult proxyResult;

    // no args
    awsResult = awsClient.listTables();
    proxyResult = proxyClient.listTables();
    assertEquals(awsResult, proxyResult);

    // startTableName
    awsResult = awsClient.listTables("bbb");
    proxyResult = proxyClient.listTables("bbb");
    assertEquals(awsResult, proxyResult);

    // limit
    awsResult = awsClient.listTables(2);
    proxyResult = proxyClient.listTables(2);
    assertEquals(awsResult, proxyResult);

    // start + limit
    awsResult = awsClient.listTables("bbb", 2);
    proxyResult = proxyClient.listTables("bbb", 2);
    assertEquals(awsResult, proxyResult);

    // overflow
    awsResult = awsClient.listTables(100);
    proxyResult = proxyClient.listTables(100);
    assertEquals(awsResult, proxyResult);
  }
}
