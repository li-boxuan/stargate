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
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.stargate.sgv2.docsapi.service.write.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.stargate.bridge.grpc.Values;
import io.stargate.bridge.proto.QueryOuterClass.BatchQuery;
import io.stargate.sgv2.docsapi.DocsApiTestSchemaProvider;
import io.stargate.sgv2.docsapi.api.exception.ErrorCode;
import io.stargate.sgv2.docsapi.api.exception.ErrorCodeRuntimeException;
import io.stargate.sgv2.docsapi.api.properties.document.DocumentProperties;
import io.stargate.sgv2.docsapi.testprofiles.MaxDepth4TestProfile;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(MaxDepth4TestProfile.class)
class DeleteSubDocumentArrayQueryBuilderTest {

  @Inject DocsApiTestSchemaProvider schemaProvider;
  @Inject DocumentProperties documentProperties;

  @Nested
  class BuildQuery {

    @Test
    public void happyPath() {
      List<String> subDocumentPath = Arrays.asList("one", "two");
      DeleteSubDocumentArrayQueryBuilder queryBuilder =
          new DeleteSubDocumentArrayQueryBuilder(subDocumentPath, documentProperties);

      BatchQuery query =
          queryBuilder.buildQuery(
              schemaProvider.getKeyspace().getName(), schemaProvider.getTable().getName());

      String expected =
          String.format(
              "DELETE FROM %s.%s USING TIMESTAMP ? WHERE key = ? AND p0 = ? AND p1 = ? AND p2 >= ? AND p2 <= ?",
              schemaProvider.getKeyspace().getName(), schemaProvider.getTable().getName());
      assertThat(query.getCql()).isEqualTo(expected);
    }

    @Test
    public void happyPathEmpty() {
      List<String> subDocumentPath = Collections.emptyList();
      DeleteSubDocumentArrayQueryBuilder queryBuilder =
          new DeleteSubDocumentArrayQueryBuilder(subDocumentPath, documentProperties);

      BatchQuery query =
          queryBuilder.buildQuery(
              schemaProvider.getKeyspace().getName(), schemaProvider.getTable().getName());

      String expected =
          String.format(
              "DELETE FROM %s.%s USING TIMESTAMP ? WHERE key = ? AND p0 >= ? AND p0 <= ?",
              schemaProvider.getKeyspace().getName(), schemaProvider.getTable().getName());
      assertThat(query.getCql()).isEqualTo(expected);
    }

    @Test
    public void maxDepthExceeded() {
      // required one more depth after the path
      List<String> subDocumentPath = Arrays.asList("1", "2", "3", "4");
      DeleteSubDocumentArrayQueryBuilder queryBuilder =
          new DeleteSubDocumentArrayQueryBuilder(subDocumentPath, documentProperties);

      Throwable throwable =
          catchThrowable(
              () ->
                  queryBuilder.buildQuery(
                      schemaProvider.getKeyspace().getName(), schemaProvider.getTable().getName()));

      assertThat(throwable)
          .isInstanceOf(ErrorCodeRuntimeException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCS_API_GENERAL_DEPTH_EXCEEDED);
    }
  }

  @Nested
  class Bind {

    @Test
    public void happyPath() {
      List<String> subDocumentPath = Arrays.asList("one", "two");
      DeleteSubDocumentArrayQueryBuilder queryBuilder =
          new DeleteSubDocumentArrayQueryBuilder(subDocumentPath, documentProperties);
      BatchQuery query =
          queryBuilder.buildQuery(
              schemaProvider.getKeyspace().getName(), schemaProvider.getTable().getName());
      assertThat(query.getCql())
          .isEqualTo(
              String.format(
                  "DELETE FROM %s.%s USING TIMESTAMP ? WHERE key = ? AND p0 = ? AND p1 = ? AND p2 >= ? AND p2 <= ?",
                  schemaProvider.getKeyspace().getName(), schemaProvider.getTable().getName()));

      long timestamp = RandomUtils.nextLong();
      String documentId = RandomStringUtils.randomAlphanumeric(16);

      BatchQuery boundQuery = queryBuilder.bind(query, documentId, timestamp);
      assertThat(boundQuery.getValues().getValuesList())
          .containsExactly(
              Values.of(timestamp),
              Values.of(documentId),
              Values.of("one"),
              Values.of("two"),
              Values.of("[000000]"),
              Values.of("[999999]"));
    }
  }
}
