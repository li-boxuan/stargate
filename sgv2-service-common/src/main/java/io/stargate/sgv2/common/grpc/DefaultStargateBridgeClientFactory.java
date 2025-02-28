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
package io.stargate.sgv2.common.grpc;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.grpc.Channel;
import io.stargate.bridge.proto.Schema;
import io.stargate.bridge.proto.Schema.CqlKeyspaceDescribe;
import io.stargate.bridge.proto.Schema.SchemaRead;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

class DefaultStargateBridgeClientFactory implements StargateBridgeClientFactory {

  private final Channel channel;
  private final int timeoutSeconds;
  private final SchemaRead.SourceApi sourceApi;
  private final Cache<String, CqlKeyspaceDescribe> keyspaceCache =
      Caffeine.newBuilder().maximumSize(1000).expireAfterAccess(5, TimeUnit.MINUTES).build();
  private final LazyReference<CompletionStage<Schema.SupportedFeaturesResponse>>
      supportedFeaturesResponse = new LazyReference<>();

  DefaultStargateBridgeClientFactory(
      Channel channel, int timeoutSeconds, SchemaRead.SourceApi sourceApi) {
    this.channel = channel;
    this.timeoutSeconds = timeoutSeconds;
    this.sourceApi = sourceApi;
  }

  @Override
  public StargateBridgeClient newClient(String authToken, Optional<String> tenantId) {
    return new DefaultStargateBridgeClient(
        channel,
        authToken,
        tenantId,
        timeoutSeconds,
        keyspaceCache,
        supportedFeaturesResponse,
        sourceApi);
  }
}
