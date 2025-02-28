/*
 * Copyright DataStax, Inc. and/or The Stargate Authors
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
package io.stargate.sgv2.common.cql.builder;

import io.stargate.sgv2.common.cql.ColumnUtils;
import javax.annotation.Nullable;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;

@Immutable(prehash = true)
public interface Column {
  String name();

  /** TODO do we ever need a more structured representation? */
  @Nullable
  String type();

  @Nullable
  Kind kind();

  @Nullable
  Order order();

  @Value.Lazy
  default String cqlName() {
    return ColumnUtils.maybeQuote(name());
  }

  static Column reference(String name) {
    return ImmutableColumn.builder().name(name).build();
  }

  enum Kind {
    PARTITION_KEY,
    CLUSTERING,
    REGULAR,
    STATIC,
  }

  enum Order {
    ASC,
    DESC,
  }
}
