package io.stargate.sgv2.dynamosvc.dynamo;

import static com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperFieldModel.DynamoDBAttributeType.valueOf;
import static java.lang.Integer.min;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperFieldModel;
import com.amazonaws.services.dynamodbv2.model.*;
import io.stargate.bridge.proto.QueryOuterClass;
import io.stargate.sgv2.common.cql.builder.*;
import io.stargate.sgv2.common.grpc.StargateBridgeClient;
import io.stargate.sgv2.dynamosvc.models.PrimaryKey;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class TableProxy extends Proxy {
  public CreateTableResult createTable(
      CreateTableRequest createTableRequest, StargateBridgeClient bridge) throws IOException {
    final String tableName = createTableRequest.getTableName();
    List<Column> columns = new ArrayList<>();
    final PrimaryKey primaryKey = getPrimaryKey(createTableRequest.getKeySchema());
    for (AttributeDefinition columnDef : createTableRequest.getAttributeDefinitions()) {
      final String columnName = columnDef.getAttributeName();
      final DynamoDBMapperFieldModel.DynamoDBAttributeType type =
          valueOf(columnDef.getAttributeType());
      ImmutableColumn.Builder column =
          ImmutableColumn.builder().name(columnName).type(DataTypeMapper.fromDynamo(type));
      if (columnName.equals(primaryKey.getPartitionKey())) {
        column.kind(Column.Kind.PARTITION_KEY);
      } else if (columnName.equals(primaryKey.getClusteringKey())) {
        column.kind(Column.Kind.CLUSTERING);
      }
      columns.add(column.build());
    }

    QueryOuterClass.Query query =
        new QueryBuilder().create().table(KEYSPACE_NAME, tableName).column(columns).build();

    bridge.executeQuery(query);

    TableDescription newTableDesc =
        this.getTableDescription(
            tableName,
            createTableRequest.getAttributeDefinitions(),
            createTableRequest.getKeySchema());
    return (new CreateTableResult()).withTableDescription(newTableDesc);
  }

  public DeleteTableResult deleteTable(
      DeleteTableRequest deleteTableRequest, StargateBridgeClient bridge) throws IOException {
    final String tableName = deleteTableRequest.getTableName();
    QueryOuterClass.Query query = new QueryBuilder().drop().table(KEYSPACE_NAME, tableName).build();

    bridge.executeQuery(query);
    // TODO: throws appropriate exception when it fails

    TableDescription tableDesc =
        new TableDescription()
            .withTableName(tableName)
            .withTableStatus(TableStatus.DELETING)
            .withTableArn(tableName);
    return (new DeleteTableResult()).withTableDescription(tableDesc);
  }

  public ListTablesResult listTables(
      ListTablesRequest listTablesRequest, StargateBridgeClient bridge) throws IOException {
    final String startTableName = listTablesRequest.getExclusiveStartTableName();
    Integer limit = listTablesRequest.getLimit();
    if (limit == null || limit > 100) {
      limit = 100;
    }
    QueryBuilder.QueryBuilder__21 queryBuilder =
        new QueryBuilder()
            .select()
            .column("table_name")
            .from("system_schema", "tables")
            .where(
                "keyspace_name",
                Predicate.EQ,
                DataMapper.fromDynamo(DataMapper.toDynamo(KEYSPACE_NAME)));
    QueryOuterClass.Response response = bridge.executeQuery(queryBuilder.build());
    List<String> allTableNames =
        response.getResultSet().getRowsList().stream()
            .map(row -> row.getValues(0))
            .map(QueryOuterClass.Value::getString)
            .collect(Collectors.toList());
    int startIndex = 0;
    if (startTableName != null && !startTableName.isEmpty()) {
      startIndex = allTableNames.indexOf(startTableName);
      startIndex = startIndex == -1 ? 0 : startIndex + 1;
    }
    if (startIndex == allTableNames.size()) {
      return new ListTablesResult();
    }
    int endIndex = min(allTableNames.size(), startIndex + limit);
    List<String> tableNames = allTableNames.subList(startIndex, endIndex);
    if (endIndex == allTableNames.size()) {
      return new ListTablesResult().withTableNames(tableNames);
    }
    return new ListTablesResult()
        .withTableNames(tableNames)
        .withLastEvaluatedTableName(tableNames.get(endIndex - 1));
  }

  private TableDescription getTableDescription(
      String tableName,
      Collection<AttributeDefinition> attributeDefinitions,
      Collection<KeySchemaElement> keySchema) {
    TableDescription tableDescription =
        (new TableDescription())
            .withTableName(tableName)
            .withAttributeDefinitions(attributeDefinitions)
            .withKeySchema(keySchema)
            .withTableStatus(TableStatus.ACTIVE)
            .withCreationDateTime(new Date())
            .withTableArn(tableName);

    return tableDescription;
  }
}
