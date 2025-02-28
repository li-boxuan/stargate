/*
 * Copyright The Stargate Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stargate.sgv2.graphql.integration.cqlfirst;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.stargate.sgv2.common.testresource.StargateTestResource;
import io.stargate.sgv2.graphql.integration.util.CqlFirstIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@QuarkusIntegrationTest
@QuarkusTestResource(
    value = StargateTestResource.class,
    initArgs =
        @ResourceArg(name = StargateTestResource.Options.DISABLE_FIXED_TOKEN, value = "true"))
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CollectionsIntegrationTest extends CqlFirstIntegrationTest {

  @BeforeAll
  public void createSchema() {
    session.execute(
        "CREATE TABLE IF NOT EXISTS \"CollectionsSimple\" (\n"
            + "    id uuid PRIMARY KEY,\n"
            + "    \"listValue1\" frozen<list<int>>,\n"
            + "    \"setValue1\" frozen<set<text>>,\n"
            + "    \"mapValue1\" frozen<map<int, text>>,\n"
            + ")");
    session.execute(
        "CREATE TABLE IF NOT EXISTS \"CollectionsNested\" (\n"
            + "    id uuid PRIMARY KEY,\n"
            + "    \"listValue1\" frozen<list<frozen<map<int, text>>>>,\n"
            + "    \"setValue1\" frozen<list<frozen<set<uuid>>>>,\n"
            + "    \"mapValue1\" frozen<map<uuid, frozen<map<bigint, text>>>>\n"
            + ")");
  }

  @Test
  public void shouldInsertAndUpdateSimpleListSetsAndMaps() {
    // When inserting a new row:
    Map<String, Object> response =
        client.executeDmlQuery(
            keyspaceId.asInternal(),
            "mutation {\n"
                + "  insertCollectionsSimple(value: {\n"
                + "      id: \"792d0a56-bb46-4bc2-bc41-5f4a94a83da9\"\n"
                + "      listValue1: [1, 2, 3]\n"
                + "      setValue1: [\"a\", \"b\", \"c\"]\n"
                + "      mapValue1: [\n"
                + "        {key: 1, value: \"one\"}\n"
                + "        {key: 2, value: \"two\"}\n"
                + "      ]\n"
                + "  }) { applied }}");
    assertThat(JsonPath.<Boolean>read(response, "$.insertCollectionsSimple.applied")).isTrue();

    // Then the data can be read back:
    String getQuery =
        "{\n"
            + "  CollectionsSimple(value: {id: \"792d0a56-bb46-4bc2-bc41-5f4a94a83da9\"}) {\n"
            + "    values { listValue1, setValue1, mapValue1 { key, value } }\n"
            + "  }}";
    response = client.executeDmlQuery(keyspaceId.asInternal(), getQuery);
    List<Integer> listValue1 = JsonPath.read(response, "$.CollectionsSimple.values[0].listValue1");
    assertThat(listValue1).containsExactly(1, 2, 3);
    List<String> setValue1 = JsonPath.read(response, "$.CollectionsSimple.values[0].setValue1");
    assertThat(setValue1).containsExactly("a", "b", "c");
    List<Map<String, Object>> mapValue1 =
        JsonPath.read(response, "$.CollectionsSimple.values[0].mapValue1");
    assertThat(mapValue1).hasSize(2);
    assertThat(mapValue1.get(0)).hasSize(2).containsEntry("key", 1).containsEntry("value", "one");
    assertThat(mapValue1.get(1)).hasSize(2).containsEntry("key", 2).containsEntry("value", "two");

    // When updating the row:
    response =
        client.executeDmlQuery(
            keyspaceId.asInternal(),
            "mutation {\n"
                + "  updateCollectionsSimple(\n"
                + "    value: {\n"
                + "      id: \"792d0a56-bb46-4bc2-bc41-5f4a94a83da9\""
                + "      listValue1: [4, 5]\n"
                + "      setValue1: [\"d\"]\n"
                + "      mapValue1: [ {key: 3, value: \"three\"} ]\n"
                + "  }) { applied } }");
    assertThat(JsonPath.<Boolean>read(response, "$.updateCollectionsSimple.applied")).isTrue();

    // Then the changes are reflected:
    response = client.executeDmlQuery(keyspaceId.asInternal(), getQuery);
    listValue1 = JsonPath.read(response, "$.CollectionsSimple.values[0].listValue1");
    assertThat(listValue1).containsExactly(4, 5);
    setValue1 = JsonPath.read(response, "$.CollectionsSimple.values[0].setValue1");
    assertThat(setValue1).containsExactly("d");
    mapValue1 = JsonPath.read(response, "$.CollectionsSimple.values[0].mapValue1");
    assertThat(mapValue1).hasSize(1);
    assertThat(mapValue1.get(0)).hasSize(2).containsEntry("key", 3).containsEntry("value", "three");
  }

  @Test
  public void shouldInsertAndUpdateNestedListSetsAndMaps() {
    // When inserting a new row:
    Map<String, Object> response =
        client.executeDmlQuery(
            keyspaceId.asInternal(),
            "mutation {\n"
                + "  insertCollectionsNested(value: {\n"
                + "      id: \"792d0a56-bb46-4bc2-bc41-5f4a94a83da9\"\n"
                + "      listValue1: [ [ {key: 3, value: \"three\"} ] ]\n"
                + "      setValue1: [ [ \"792d0a56-bb46-4bc2-bc41-5f4a94a83da9\" ] ]\n"
                + "      mapValue1: [\n"
                + "        {\n"
                + "          key: \"792d0a56-bb46-4bc2-bc41-5f4a94a83da9\"\n"
                + "          value: [ { key: 123, value: \"one-two-three\" } ]\n"
                + "        }\n"
                + "      ]\n"
                + "  }) { applied }}");
    assertThat(JsonPath.<Boolean>read(response, "$.insertCollectionsNested.applied")).isTrue();

    // Then the data can be read back:
    String getQuery =
        "{\n"
            + "  CollectionsNested(value: {id: \"792d0a56-bb46-4bc2-bc41-5f4a94a83da9\"}) {\n"
            + "    values {\n"
            + "      listValue1 { key, value}\n"
            + "      setValue1\n"
            + "      mapValue1 { key, value { key, value } } }\n"
            + "  }}";
    response = client.executeDmlQuery(keyspaceId.asInternal(), getQuery);
    List<List<Map<String, Object>>> listValue1 =
        JsonPath.read(response, "$.CollectionsNested.values[0].listValue1");
    assertThat(listValue1).hasSize(1);
    List<Map<String, Object>> nestedMapInListValue1 = listValue1.get(0);
    assertThat(nestedMapInListValue1).hasSize(1);
    assertThat(nestedMapInListValue1.get(0))
        .hasSize(2)
        .containsEntry("key", 3)
        .containsEntry("value", "three");

    List<List<String>> setValue1 =
        JsonPath.read(response, "$.CollectionsNested.values[0].setValue1");
    assertThat(setValue1).hasSize(1);
    List<String> nestedSetInSetValue1 = setValue1.get(0);
    assertThat(nestedSetInSetValue1).containsExactly("792d0a56-bb46-4bc2-bc41-5f4a94a83da9");

    List<Map<String, Object>> mapValue1 =
        JsonPath.read(response, "$.CollectionsNested.values[0].mapValue1");
    assertThat(mapValue1).hasSize(1);
    Object keyInMapValue1 = mapValue1.get(0).get("key");
    assertThat(keyInMapValue1).isEqualTo("792d0a56-bb46-4bc2-bc41-5f4a94a83da9");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nestedMapInMapValue1 =
        (List<Map<String, Object>>) mapValue1.get(0).get("value");
    assertThat(nestedMapInMapValue1).hasSize(1);
    assertThat(nestedMapInMapValue1.get(0))
        .hasSize(2)
        .containsEntry("key", "123")
        .containsEntry("value", "one-two-three");
  }

  @Test
  public void shouldInsertNullCollections() {
    // When inserting a new row:
    Map<String, Object> response =
        client.executeDmlQuery(
            keyspaceId.asInternal(),
            "mutation {\n"
                + "  insertCollectionsSimple(value: {\n"
                + "      id: \"792d0a56-bb46-4bc2-bc41-5f4a94a83da9\"\n"
                + "      listValue1: null\n"
                + "      setValue1: null\n"
                + "      mapValue1: null\n"
                + "  }) { applied }}");
    assertThat(JsonPath.<Boolean>read(response, "$.insertCollectionsSimple.applied")).isTrue();

    // Then the data can be read back:
    String getQuery =
        "{\n"
            + "  CollectionsSimple(value: {id: \"792d0a56-bb46-4bc2-bc41-5f4a94a83da9\"}) {\n"
            + "    values { listValue1, setValue1, mapValue1 { key, value } }\n"
            + "  }}";
    response = client.executeDmlQuery(keyspaceId.asInternal(), getQuery);
    List<Integer> listValue1 = JsonPath.read(response, "$.CollectionsSimple.values[0].listValue1");
    assertThat(listValue1).isNull();
    List<String> setValue1 = JsonPath.read(response, "$.CollectionsSimple.values[0].setValue1");
    assertThat(setValue1).isNull();
    List<Map<String, Object>> mapValue1 =
        JsonPath.read(response, "$.CollectionsSimple.values[0].mapValue1");
    assertThat(mapValue1).isNull();
  }
}
