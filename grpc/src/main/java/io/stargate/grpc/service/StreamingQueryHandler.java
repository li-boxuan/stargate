package io.stargate.grpc.service;

import io.stargate.db.Persistence;
import io.stargate.proto.QueryOuterClass;
import java.util.concurrent.ScheduledExecutorService;

public class StreamingQueryHandler extends QueryHandler {
  private final SuccessHandler successHandler;

  StreamingQueryHandler(
      QueryOuterClass.Query query,
      Persistence.Connection connection,
      Persistence persistence,
      ScheduledExecutorService executor,
      int schemaAgreementRetries,
      SuccessHandler successHandler,
      ExceptionHandler exceptionHandler) {
    super(query, connection, persistence, executor, schemaAgreementRetries, exceptionHandler);
    this.successHandler = successHandler;
  }

  @Override
  protected void setSuccess(QueryOuterClass.Response response) {
    successHandler.handleResponse(
        QueryOuterClass.StreamingResponse.newBuilder().setResponse(response).build());
  }
}
