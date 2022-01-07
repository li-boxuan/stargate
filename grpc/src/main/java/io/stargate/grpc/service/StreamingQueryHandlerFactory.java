package io.stargate.grpc.service;

import io.stargate.db.Persistence;
import io.stargate.proto.QueryOuterClass;
import java.util.concurrent.ScheduledExecutorService;

public class StreamingQueryHandlerFactory
    implements StreamingHandlerFactory<QueryOuterClass.Query> {

  private final Persistence.Connection connection;
  private final Persistence persistence;
  private final ScheduledExecutorService executor;
  private final int schemaAgreementRetries;

  public StreamingQueryHandlerFactory(
      Persistence.Connection connection,
      Persistence persistence,
      ScheduledExecutorService executor,
      int schemaAgreementRetries) {
    this.connection = connection;
    this.persistence = persistence;
    this.executor = executor;
    this.schemaAgreementRetries = schemaAgreementRetries;
  }

  @Override
  public MessageHandler<QueryOuterClass.Query, ?> create(
      QueryOuterClass.Query query,
      SuccessHandler successHandler,
      ExceptionHandler exceptionHandler) {
    return new StreamingQueryHandler(
        query,
        connection,
        persistence,
        executor,
        schemaAgreementRetries,
        successHandler,
        exceptionHandler);
  }
}
