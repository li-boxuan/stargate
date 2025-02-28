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

import io.grpc.Channel;
import io.stargate.bridge.proto.Schema.SchemaRead;
import java.util.Optional;

public interface StargateBridgeClientFactory {

  /**
   * @param timeoutSeconds the timeout for the gRPC queries issued through clients created with this
   *     factory. Note that it is measured from the moment {@link #newClient} was invoked (if the
   *     same client is used for multiple queries, the timeout does not reset between each query).
   */
  static StargateBridgeClientFactory newInstance(
      Channel channel, int timeoutSeconds, SchemaRead.SourceApi sourceApi) {
    return new DefaultStargateBridgeClientFactory(channel, timeoutSeconds, sourceApi);
  }

  StargateBridgeClient newClient(String authToken, Optional<String> tenantId);
}
