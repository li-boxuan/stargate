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
import com.google.protobuf.BytesValue;
import com.google.protobuf.Int32Value;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.stargate.bridge.grpc.StargateBearerToken;
import io.stargate.bridge.proto.QueryOuterClass.Batch;
import io.stargate.bridge.proto.QueryOuterClass.Query;
import io.stargate.bridge.proto.QueryOuterClass.QueryParameters;
import io.stargate.bridge.proto.QueryOuterClass.Response;
import io.stargate.bridge.proto.QueryOuterClass.ResultSet;
import io.stargate.bridge.proto.QueryOuterClass.Row;
import io.stargate.bridge.proto.Schema;
import io.stargate.bridge.proto.Schema.AuthorizeSchemaReadsRequest;
import io.stargate.bridge.proto.Schema.AuthorizeSchemaReadsResponse;
import io.stargate.bridge.proto.Schema.CqlKeyspaceDescribe;
import io.stargate.bridge.proto.Schema.DescribeKeyspaceQuery;
import io.stargate.bridge.proto.Schema.QueryWithSchema;
import io.stargate.bridge.proto.Schema.QueryWithSchemaResponse;
import io.stargate.bridge.proto.Schema.SchemaRead;
import io.stargate.bridge.proto.Schema.SchemaRead.SourceApi;
import io.stargate.bridge.proto.Schema.SupportedFeaturesRequest;
import io.stargate.bridge.proto.Schema.SupportedFeaturesResponse;
import io.stargate.bridge.proto.StargateBridgeGrpc;
import io.stargate.sgv2.common.futures.Futures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.codec.binary.Hex;

class DefaultStargateBridgeClient implements StargateBridgeClient {

  private static final int TIMEOUT_SECONDS = 10;
  static final Metadata.Key<String> TENANT_ID_KEY =
      Metadata.Key.of("x-tenant-id", Metadata.ASCII_STRING_MARSHALLER);
  static final Query SELECT_KEYSPACE_NAMES =
      Query.newBuilder().setCql("SELECT keyspace_name FROM system_schema.keyspaces").build();
  private static final SupportedFeaturesRequest SUPPORTED_FEATURES_REQUEST =
      SupportedFeaturesRequest.newBuilder().build();

  private final Channel channel;
  private final CallOptions callOptions;
  // A note on tenant handling: the tenant id is sent in the gRPC metadata, so the bridge will
  // handle it transparently; client-facing methods like `executeQueryAsync()` and
  // `authorizeSchemaReadsAsync()` use "undecorated" keyspace names.
  // The only case where we need to decorate explicitly is the keyspace cache, because it is common
  // to all clients, therefore it can contain keyspaces with the same name but different tenant ids.
  private final String tenantPrefix;
  private final Cache<String, CqlKeyspaceDescribe> keyspaceCache;
  private final LazyReference<CompletionStage<SupportedFeaturesResponse>> supportedFeaturesResponse;
  private final SourceApi sourceApi;

  DefaultStargateBridgeClient(
      Channel channel,
      String authToken,
      Optional<String> tenantId,
      int timeoutSeconds,
      Cache<String, CqlKeyspaceDescribe> keyspaceCache,
      LazyReference<CompletionStage<SupportedFeaturesResponse>> supportedFeaturesResponse,
      SourceApi sourceApi) {
    this.channel = tenantId.map(i -> addMetadata(channel, i)).orElse(channel);
    this.callOptions =
        CallOptions.DEFAULT
            .withDeadlineAfter(timeoutSeconds, TimeUnit.SECONDS)
            .withCallCredentials(new StargateBearerToken(authToken));
    this.tenantPrefix = tenantId.map(this::encodeKeyspacePrefix).orElse("");
    this.keyspaceCache = keyspaceCache;
    this.supportedFeaturesResponse = supportedFeaturesResponse;
    this.sourceApi = sourceApi;
  }

  @Override
  public CompletionStage<Response> executeQueryAsync(Query query) {
    ClientCall<Query, Response> call =
        channel.newCall(StargateBridgeGrpc.getExecuteQueryMethod(), callOptions);
    UnaryStreamObserver<Response> observer = new UnaryStreamObserver<>();
    ClientCalls.asyncUnaryCall(call, query, observer);
    return observer.asFuture();
  }

  @Override
  public CompletionStage<Response> executeQueryAsync(
      String keyspaceName,
      String tableName,
      Function<Optional<Schema.CqlTable>, Query> queryProducer) {
    String decoratedKeyspaceName = addTenantPrefix(keyspaceName);
    CqlKeyspaceDescribe keyspace = keyspaceCache.getIfPresent(decoratedKeyspaceName);
    if (keyspace == null) {
      // Nothing in our local cache, check if the bridge has a more recent version
      return forceFetchAndExecute(keyspaceName, decoratedKeyspaceName, tableName, queryProducer);
    } else {
      // Build the query with our local version
      Query query;
      try {
        query = produceQuery(queryProducer, Optional.of(keyspace), tableName);
      } catch (Exception ignored) {
        // Always retry with the latest version, in case the error is caused by stale metadata
        // (e.g. using a table that has not yet replicated to our local cache)
        return forceFetchAndExecute(keyspaceName, decoratedKeyspaceName, tableName, queryProducer);
      }
      // Execute optimistically
      ClientCall<QueryWithSchema, QueryWithSchemaResponse> call =
          channel.newCall(StargateBridgeGrpc.getExecuteQueryWithSchemaMethod(), callOptions);
      UnaryStreamObserver<QueryWithSchemaResponse> observer = new UnaryStreamObserver<>();
      ClientCalls.asyncUnaryCall(
          call,
          QueryWithSchema.newBuilder()
              .setQuery(query)
              .setKeyspaceName(keyspaceName)
              .setKeyspaceHash(keyspace.getHash().getValue())
              .build(),
          observer);
      return observer
          .asFuture()
          .thenCompose(
              withSchemaResponse -> {
                QueryWithSchemaResponse.InnerCase innerCase = withSchemaResponse.getInnerCase();
                switch (innerCase) {
                  case RESPONSE:
                    // Success, the keyspace hadn't changed on the bridge
                    return CompletableFuture.completedFuture(withSchemaResponse.getResponse());
                  case NEW_KEYSPACE:
                    // The keyspace was updated, retry with that version
                    CqlKeyspaceDescribe ks = withSchemaResponse.getNewKeyspace();
                    keyspaceCache.put(decoratedKeyspaceName, ks);
                    return executeQueryAsync(
                        produceQuery(queryProducer, Optional.of(keyspace), tableName));
                  case NO_KEYSPACE:
                    // The keyspace was deleted
                    keyspaceCache.invalidate(decoratedKeyspaceName);
                    return executeQueryAsync(
                        produceQuery(queryProducer, Optional.empty(), tableName));
                  default:
                    throw new IllegalStateException(
                        "Invalid bridge response, unexpected inner " + innerCase);
                }
              });
    }
  }

  private CompletionStage<Response> forceFetchAndExecute(
      String keyspaceName,
      String decoratedKeyspaceName,
      String tableName,
      Function<Optional<Schema.CqlTable>, Query> queryProducer) {
    return getKeyspaceAsync(keyspaceName, false)
        .thenCompose(
            newKeyspace -> {
              newKeyspace.ifPresent(ks -> keyspaceCache.put(decoratedKeyspaceName, ks));
              return executeQueryAsync(produceQuery(queryProducer, newKeyspace, tableName));
            });
  }

  private Query produceQuery(
      Function<Optional<Schema.CqlTable>, Query> queryProducer,
      Optional<CqlKeyspaceDescribe> maybeKeyspace,
      String tableName) {
    return queryProducer.apply(
        maybeKeyspace.flatMap(
            keyspace ->
                keyspace.getTablesList().stream()
                    .filter(t -> t.getName().equals(tableName))
                    .findFirst()));
  }

  @Override
  public CompletionStage<Response> executeBatchAsync(Batch batch) {
    ClientCall<Batch, Response> call =
        channel.newCall(StargateBridgeGrpc.getExecuteBatchMethod(), callOptions);
    UnaryStreamObserver<Response> observer = new UnaryStreamObserver<>();
    ClientCalls.asyncUnaryCall(call, batch, observer);
    return observer.asFuture();
  }

  @Override
  public CompletionStage<Optional<CqlKeyspaceDescribe>> getKeyspaceAsync(
      String keyspaceName, boolean checkIfAuthorized) {
    if (checkIfAuthorized) {
      return authorizeSchemaReadAsync(SchemaReads.keyspace(keyspaceName, sourceApi))
          .thenCompose(
              authorized -> {
                if (!authorized) {
                  throw new UnauthorizedKeyspaceException(keyspaceName);
                }
                return getAuthorizedKeyspace(keyspaceName).thenApply(Optional::ofNullable);
              });
    } else {
      return getAuthorizedKeyspace(keyspaceName).thenApply(Optional::ofNullable);
    }
  }

  private CompletionStage<CqlKeyspaceDescribe> getAuthorizedKeyspace(String keyspaceName) {
    CompletableFuture<CqlKeyspaceDescribe> result = new CompletableFuture<>();

    String decoratedKeyspaceName = decorateKeyspaceName(keyspaceName);

    CqlKeyspaceDescribe cached = keyspaceCache.getIfPresent(decoratedKeyspaceName);
    Optional<Integer> cachedHash =
        Optional.ofNullable(cached)
            .filter(CqlKeyspaceDescribe::hasHash)
            .map(d -> d.getHash().getValue());

    // The result is cached locally, but we always hit the bridge anyway, to check if it has a more
    // recent version.
    fetchKeyspaceFromBridge(keyspaceName, cachedHash)
        .whenComplete(
            (fetched, error) -> {
              if (error instanceof StatusRuntimeException
                  && ((StatusRuntimeException) error).getStatus().getCode()
                      == Status.Code.NOT_FOUND) {
                // Keyspace does not exist. Update the cache if we previously thought it did.
                if (cached != null) {
                  keyspaceCache.invalidate(decoratedKeyspaceName);
                }
                result.complete(null);
              } else if (error != null) {
                result.completeExceptionally(error);
              } else if (!fetched.hasCqlKeyspace()) {
                // Empty response means the hash hasn't changed, we can keep using the
                // cached version.
                result.complete(cached);
              } else {
                keyspaceCache.put(decoratedKeyspaceName, fetched);
                result.complete(fetched);
              }
            });

    return result;
  }

  private CompletionStage<CqlKeyspaceDescribe> fetchKeyspaceFromBridge(
      String keyspaceName, Optional<Integer> hash) {

    DescribeKeyspaceQuery.Builder query =
        DescribeKeyspaceQuery.newBuilder().setKeyspaceName(keyspaceName);
    hash.ifPresent(h -> query.setHash(Int32Value.of(h)));

    ClientCall<DescribeKeyspaceQuery, CqlKeyspaceDescribe> call =
        channel.newCall(StargateBridgeGrpc.getDescribeKeyspaceMethod(), callOptions);
    UnaryStreamObserver<CqlKeyspaceDescribe> observer = new UnaryStreamObserver<>();
    ClientCalls.asyncUnaryCall(call, query.build(), observer);
    return observer.asFuture();
  }

  @Override
  public CompletionStage<List<CqlKeyspaceDescribe>> getAllKeyspacesAsync() {
    CompletionStage<List<String>> allNamesFuture = getKeyspaceNames(new ArrayList<>(), null);
    CompletionStage<List<String>> authorizedNamesFuture =
        allNamesFuture.thenCompose(
            names -> {
              List<SchemaRead> reads =
                  names.stream()
                      .map(n -> SchemaReads.keyspace(n, sourceApi))
                      .collect(Collectors.toList());
              return authorizeSchemaReadsAsync(reads)
                  .thenApply(authorizations -> removeUnauthorized(names, authorizations));
            });
    return authorizedNamesFuture.thenCompose(this::getAuthorizedKeyspaces);
  }

  private <T> List<T> removeUnauthorized(List<T> elements, List<Boolean> authorizations) {
    assert elements.size() == authorizations.size();
    List<T> filtered = new ArrayList<>(elements.size());
    for (int i = 0; i < elements.size(); i++) {
      if (authorizations.get(i)) {
        filtered.add(elements.get(i));
      }
    }
    return filtered;
  }

  private CompletionStage<List<CqlKeyspaceDescribe>> getAuthorizedKeyspaces(
      List<String> keyspaceNames) {
    List<CompletionStage<CqlKeyspaceDescribe>> keyspaceFutures =
        keyspaceNames.stream().map(this::getAuthorizedKeyspace).collect(Collectors.toList());
    // Filter out nulls in case any keyspace was removed since we fetched the names
    return Futures.sequence(keyspaceFutures)
        .thenApply(
            keyspaces -> keyspaces.stream().filter(Objects::nonNull).collect(Collectors.toList()));
  }

  @Override
  public String decorateKeyspaceName(String keyspaceName) {
    return addTenantPrefix(keyspaceName);
  }

  @Override
  public CompletionStage<Optional<Schema.CqlTable>> getTableAsync(
      String keyspaceName, String tableName, boolean checkIfAuthorized) {
    if (checkIfAuthorized) {
      return authorizeSchemaReadAsync(SchemaReads.table(keyspaceName, tableName, sourceApi))
          .thenCompose(
              authorized -> {
                if (!authorized) {
                  throw new UnauthorizedTableException(keyspaceName, tableName);
                }
                return getAuthorizedTable(keyspaceName, tableName);
              });
    } else {
      return getAuthorizedTable(keyspaceName, tableName);
    }
  }

  private CompletionStage<Optional<Schema.CqlTable>> getAuthorizedTable(
      String keyspaceName, String tableName) {
    return getAuthorizedKeyspace(keyspaceName)
        .thenApply(
            ks ->
                ks == null
                    ? Optional.empty()
                    : ks.getTablesList().stream()
                        .filter(t -> t.getName().equals(tableName))
                        .findFirst());
  }

  @Override
  public CompletionStage<List<Schema.CqlTable>> getTablesAsync(String keyspaceName) {
    return getAuthorizedKeyspace(keyspaceName)
        .thenCompose(
            ks -> {
              if (ks == null) {
                return CompletableFuture.completedFuture(Collections.emptyList());
              } else {
                List<Schema.CqlTable> tables = ks.getTablesList();
                List<SchemaRead> reads =
                    tables.stream()
                        .map(t -> SchemaReads.table(keyspaceName, t.getName(), sourceApi))
                        .collect(Collectors.toList());
                return authorizeSchemaReadsAsync(reads)
                    .thenApply(authorizations -> removeUnauthorized(tables, authorizations));
              }
            });
  }

  @Override
  public CompletionStage<List<Boolean>> authorizeSchemaReadsAsync(List<SchemaRead> schemaReads) {
    ClientCall<AuthorizeSchemaReadsRequest, AuthorizeSchemaReadsResponse> call =
        channel.newCall(StargateBridgeGrpc.getAuthorizeSchemaReadsMethod(), callOptions);
    UnaryStreamObserver<AuthorizeSchemaReadsResponse> observer = new UnaryStreamObserver<>();
    ClientCalls.asyncUnaryCall(
        call,
        AuthorizeSchemaReadsRequest.newBuilder().addAllSchemaReads(schemaReads).build(),
        observer);
    return observer.asFuture().thenApply(AuthorizeSchemaReadsResponse::getAuthorizedList);
  }

  @Override
  public CompletionStage<SupportedFeaturesResponse> getSupportedFeaturesAsync() {
    // This response is the same for all clients, and never changes over time. So only do the actual
    // gRPC call the first time, and then cache it.
    return supportedFeaturesResponse.get(this::getSupportedFeaturesFromBridge);
  }

  private CompletionStage<SupportedFeaturesResponse> getSupportedFeaturesFromBridge() {
    ClientCall<SupportedFeaturesRequest, SupportedFeaturesResponse> call =
        channel.newCall(StargateBridgeGrpc.getGetSupportedFeaturesMethod(), callOptions);
    UnaryStreamObserver<SupportedFeaturesResponse> observer = new UnaryStreamObserver<>();
    ClientCalls.asyncUnaryCall(call, SUPPORTED_FEATURES_REQUEST, observer);
    return observer.asFuture();
  }

  private CompletionStage<List<String>> getKeyspaceNames(
      List<String> accumulator, BytesValue pagingState) {
    Query query =
        (pagingState == null)
            ? SELECT_KEYSPACE_NAMES
            : Query.newBuilder(SELECT_KEYSPACE_NAMES)
                .setParameters(QueryParameters.newBuilder().setPagingState(pagingState).build())
                .build();
    return executeQueryAsync(query)
        .thenCompose(
            response -> {
              ResultSet resultSet = response.getResultSet();
              for (Row row : resultSet.getRowsList()) {
                accumulator.add(row.getValues(0).getString());
              }
              return (resultSet.hasPagingState())
                  ? getKeyspaceNames(accumulator, resultSet.getPagingState())
                  : CompletableFuture.completedFuture(accumulator);
            });
  }

  private Channel addMetadata(Channel channel, String tenantId) {
    Metadata metadata = new Metadata();
    metadata.put(TENANT_ID_KEY, tenantId);
    return ClientInterceptors.intercept(
        channel, MetadataUtils.newAttachHeadersInterceptor(metadata));
  }

  private String encodeKeyspacePrefix(String tenantId) {
    return Hex.encodeHexString(tenantId.getBytes(StandardCharsets.UTF_8)) + "_";
  }

  private String addTenantPrefix(String keyspaceName) {
    return tenantPrefix + keyspaceName;
  }
}
