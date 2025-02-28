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
package io.stargate.sgv2.graphql.integration.graphqlfirst;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.stargate.sgv2.common.testresource.StargateTestResource;
import io.stargate.sgv2.graphql.integration.util.GraphqlFirstIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@QuarkusIntegrationTest
@QuarkusTestResource(
    value = StargateTestResource.class,
    initArgs =
        @ResourceArg(name = StargateTestResource.Options.DISABLE_FIXED_TOKEN, value = "true"))
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CqlTimestampDirectiveValidationIntegrationTest extends GraphqlFirstIntegrationTest {

  @Test
  @DisplayName(
      "Should fail when deploying schema with two fields with the @cql_timestamp directive UPDATE")
  public void shouldFailToDeploySchemaWithTwoFieldWithCqlTimestampDirectiveUpdate() {
    // given, when
    Map<String, Object> errors =
        client
            .getDeploySchemaErrors(
                keyspaceId.asInternal(),
                null,
                "type User @cql_input {\n"
                    + "  k: Int! @cql_column(partitionKey: true)\n"
                    + "  v: Int\n"
                    + "}\n"
                    + "type Query { users(k: Int!): User }\n"
                    + "type Mutation {\n"
                    + " updateWithWriteTimestamp(\n"
                    + "    k: Int\n"
                    + "    v: Int\n"
                    + "    write_timestamp: String @cql_timestamp\n"
                    + "    write_timestamp2: String @cql_timestamp\n"
                    + "  ): Boolean\n"
                    + "@cql_update(targetEntity: \"User\")\n"
                    + "}")
            .get(0);

    // then
    assertThat(getMappingErrors(errors))
        .contains(
            "Query updateWithWriteTimestamp: @cql_timestamp can be used on at most one argument (found write_timestamp and write_timestamp2)");
  }

  @Test
  @DisplayName(
      "Should fail when deploying schema with two fields with the @cql_timestamp directive INSERT")
  public void shouldFailToDeploySchemaWithTwoFieldWithCqlTimestampDirectiveInsert() {
    // given, when
    Map<String, Object> errors =
        client
            .getDeploySchemaErrors(
                keyspaceId.asInternal(),
                null,
                "type User @cql_input {\n"
                    + "  k: Int! @cql_column(partitionKey: true)\n"
                    + "  v: Int\n"
                    + "}\n"
                    + "type Query { users(k: Int!): User }\n"
                    + "type InsertUserResponse @cql_payload {\n"
                    + "  applied: Boolean!\n"
                    + "  user: User!\n"
                    + "}\n"
                    + "type Mutation {\n"
                    + "  insertWithWriteTimestamp(\n"
                    + "    user: UserInput!\n"
                    + "    write_timestamp: String @cql_timestamp\n"
                    + "    write_timestamp2: String @cql_timestamp\n"
                    + "): InsertUserResponse @cql_insert\n"
                    + "}")
            .get(0);

    // then
    assertThat(getMappingErrors(errors))
        .contains(
            "Mutation insertWithWriteTimestamp: inserts can't have more than two arguments: entity input and optionally a value with cql_timestamp directive");
  }

  @Test
  @DisplayName(
      "Should fail when deploying schema with a @cql_timestamp directive not proper type UPDATE")
  public void shouldFailToDeploySchemaWithCqlTimestampDirectiveNotProperTypeUpdate() {
    // given, when
    Map<String, Object> errors =
        client
            .getDeploySchemaErrors(
                keyspaceId.asInternal(),
                null,
                "type User @cql_input {\n"
                    + "  k: Int! @cql_column(partitionKey: true)\n"
                    + "  v: Int\n"
                    + "}\n"
                    + "type Query { users(k: Int!): User }\n"
                    + "type Mutation {\n"
                    + " updateWithWriteTimestamp(\n"
                    + "    k: Int\n"
                    + "    v: Int\n"
                    + "    write_timestamp: Boolean @cql_timestamp\n"
                    + "  ): Boolean\n"
                    + "@cql_update(targetEntity: \"User\")\n"
                    + "}")
            .get(0);

    // then
    assertThat(getMappingErrors(errors))
        .contains(
            "Query updateWithWriteTimestamp: argument write_timestamp annotated with @cql_timestamp must have one of the types [String, BigInt]");
  }

  @Test
  @DisplayName(
      "Should fail when deploying schema with a @cql_timestamp directive not proper type INSERT")
  public void shouldFailToDeploySchemaWithCqlTimestampDirectiveNotProperTypeInsert() {
    // given, when
    Map<String, Object> errors =
        client
            .getDeploySchemaErrors(
                keyspaceId.asInternal(),
                null,
                "type User @cql_input {\n"
                    + "  k: Int! @cql_column(partitionKey: true)\n"
                    + "  v: Int\n"
                    + "}\n"
                    + "type Query { users(k: Int!): User }\n"
                    + "type InsertUserResponse @cql_payload {\n"
                    + "  applied: Boolean!\n"
                    + "  user: User!\n"
                    + "}\n"
                    + "type Mutation {\n"
                    + "  insertWithWriteTimestamp(\n"
                    + "    user: UserInput!\n"
                    + "    write_timestamp: Boolean @cql_timestamp\n"
                    + "): InsertUserResponse @cql_insert\n"
                    + "}")
            .get(0);

    // then
    assertThat(getMappingErrors(errors))
        .contains(
            "Query insertWithWriteTimestamp: argument write_timestamp annotated with @cql_timestamp must have one of the types [String, BigInt]");
  }

  @Test
  @DisplayName(
      "Should fail when deploying INSERT schema with two arguments, none with the @cql_timestamp annotation")
  public void shouldFailToDeployInsertSchemaWithTwoArgumentsNoneWithTheCqlTimestampAnnotation() {
    // given, when
    Map<String, Object> errors =
        client
            .getDeploySchemaErrors(
                keyspaceId.asInternal(),
                null,
                "type User @cql_input {\n"
                    + "  k: Int! @cql_column(partitionKey: true)\n"
                    + "  v: Int\n"
                    + "}\n"
                    + "type InsertUserResponse @cql_payload {\n"
                    + "  applied: Boolean!\n"
                    + "  user: User!\n"
                    + "}\n"
                    + "type Query { users(k: Int!): User }\n"
                    + "type Mutation {\n"
                    + "  insertWithSecondParameterNotAnnotated(\n"
                    + "    user: UserInput!\n"
                    + "    write_timestamp: BigInt\n"
                    + "): InsertUserResponse @cql_insert\n"
                    + "}")
            .get(0);

    // then
    assertThat(getMappingErrors(errors))
        .contains(
            "Mutation insertWithSecondParameterNotAnnotated: if you provided two arguments, the second one must be annotated with cql_timestamp directive.");
  }
}
