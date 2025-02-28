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
package io.stargate.grpc.service.interceptors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.stargate.auth.AuthenticationService;
import io.stargate.auth.AuthenticationSubject;
import io.stargate.auth.UnauthorizedException;
import io.stargate.db.AuthenticatedUser;
import io.stargate.db.Persistence;
import io.stargate.db.Persistence.Connection;
import io.stargate.grpc.service.GrpcService;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import org.apache.cassandra.stargate.exceptions.UnhandledClientException;
import org.junit.jupiter.api.Test;

public class NewConnectionInterceptorTest {

  @Test
  public void correctCredentials() throws UnauthorizedException {
    AuthenticatedUser authenticatedUser = mock(AuthenticatedUser.class);
    when(authenticatedUser.name()).thenReturn("def");

    AuthenticationSubject authenticationSubject = mock(AuthenticationSubject.class);
    when(authenticationSubject.asUser()).thenReturn(authenticatedUser);

    AuthenticationService authenticationService = mock(AuthenticationService.class);
    when(authenticationService.validateToken(eq("abc"), any(Map.class)))
        .thenReturn(authenticationSubject);

    Connection connection = mock(Connection.class);
    when(connection.loggedUser()).thenReturn(Optional.of(authenticatedUser));

    Persistence persistence = mock(Persistence.class);
    when(persistence.newConnection(any())).thenReturn(connection);

    ServerCall call = mockCall();

    Metadata metadata = new Metadata();
    metadata.put(NewConnectionInterceptor.TOKEN_KEY, "abc");

    NewConnectionInterceptor interceptor =
        new NewConnectionInterceptor(persistence, authenticationService);
    interceptor.interceptCall(
        call,
        metadata,
        (c, h) -> {
          Optional<AuthenticatedUser> user = GrpcService.CONNECTION_KEY.get().loggedUser();
          assertThat(user).isPresent();
          assertThat(user.get().name()).isEqualTo("def");
          return null;
        });

    verify(call, never()).close(any(Status.class), any(Metadata.class));
    verify(authenticatedUser, times(1)).name();
  }

  @Test
  public void emptyCredentials() throws UnauthorizedException {
    Persistence persistence = mock(Persistence.class);

    AuthenticationService authenticationService = mock(AuthenticationService.class);

    ServerCallHandler next = mock(ServerCallHandler.class);
    ServerCall call = mockCall();

    Metadata metadata = new Metadata();
    NewConnectionInterceptor interceptor =
        new NewConnectionInterceptor(persistence, authenticationService);
    interceptor.interceptCall(call, metadata, next);

    verify(call, times(1))
        .close(
            argThat(
                s ->
                    s.getCode() == Status.UNAUTHENTICATED.getCode()
                        && s.getDescription().equals("No token provided")),
            any(Metadata.class));
    verify(authenticationService, never()).validateToken(anyString(), any(Map.class));
    verify(next, never()).startCall(any(ServerCall.class), any(Metadata.class));
  }

  @Test
  public void invalidCredentials() throws UnauthorizedException {
    Persistence persistence = mock(Persistence.class);

    AuthenticationService authenticationService = mock(AuthenticationService.class);
    when(authenticationService.validateToken(eq("invalid"), any(Map.class)))
        .thenThrow(new UnauthorizedException(""));

    ServerCallHandler next = mock(ServerCallHandler.class);
    ServerCall call = mockCall();

    Metadata metadata = new Metadata();
    metadata.put(NewConnectionInterceptor.TOKEN_KEY, "invalid");
    NewConnectionInterceptor interceptor =
        new NewConnectionInterceptor(persistence, authenticationService);
    interceptor.interceptCall(call, metadata, next);

    verify(call, times(1))
        .close(
            argThat(
                s ->
                    s.getCode() == Status.UNAUTHENTICATED.getCode()
                        && s.getDescription().equals("Invalid token")),
            any(Metadata.class));
    verify(authenticationService, times(1)).validateToken(eq("invalid"), any(Map.class));
    verify(next, never()).startCall(any(ServerCall.class), any(Metadata.class));
  }

  @Test
  public void unhandledClientException() throws UnauthorizedException {
    Persistence persistence = mock(Persistence.class);

    AuthenticationService authenticationService = mock(AuthenticationService.class);
    when(authenticationService.validateToken(anyString(), any(Map.class)))
        .thenThrow(new UnhandledClientException(""));

    ServerCallHandler next = mock(ServerCallHandler.class);
    ServerCall call = mockCall();

    Metadata metadata = new Metadata();
    metadata.put(NewConnectionInterceptor.TOKEN_KEY, "someToken");
    NewConnectionInterceptor interceptor =
        new NewConnectionInterceptor(persistence, authenticationService);
    interceptor.interceptCall(call, metadata, next);

    verify(call, times(1))
        .close(argThat(s -> s.getCode() == Status.UNAVAILABLE.getCode()), any(Metadata.class));
    verify(authenticationService, times(1)).validateToken(anyString(), any(Map.class));
    verify(next, never()).startCall(any(ServerCall.class), any(Metadata.class));
  }

  @Test
  public void setHostHeaderUsingAuthorityPseudoHeader() throws UnauthorizedException {
    AuthenticatedUser authenticatedUser = mock(AuthenticatedUser.class);
    when(authenticatedUser.name()).thenReturn("abc");

    AuthenticationSubject authenticationSubject = mock(AuthenticationSubject.class);
    when(authenticationSubject.asUser()).thenReturn(authenticatedUser);

    AuthenticationService authenticationService = mock(AuthenticationService.class);
    when(authenticationService.validateToken(anyString(), any(Map.class)))
        .then(
            invocation -> {
              Map<String, String> headers = invocation.getArgument(1, Map.class);
              assertThat(headers).containsEntry("host", "example.com");
              return authenticationSubject;
            });

    Connection connection = mock(Connection.class);
    when(connection.loggedUser()).thenReturn(Optional.of(authenticatedUser));

    Persistence persistence = mock(Persistence.class);
    when(persistence.newConnection(any())).thenReturn(connection);

    ServerCallHandler next = mock(ServerCallHandler.class);
    ServerCall call = mockCall();

    when(call.getAuthority()).thenReturn("example.com");

    Metadata metadata = new Metadata();
    metadata.put(NewConnectionInterceptor.TOKEN_KEY, "someToken");
    NewConnectionInterceptor interceptor =
        new NewConnectionInterceptor(persistence, authenticationService);
    interceptor.interceptCall(call, metadata, next);

    verify(authenticationService, times(1)).validateToken(anyString(), any(Map.class));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private ServerCall mockCall() {
    ServerCall call = mock(ServerCall.class);
    MethodDescriptor.Marshaller marshaller = mock(MethodDescriptor.Marshaller.class);
    MethodDescriptor methodDescriptor =
        MethodDescriptor.newBuilder()
            .setFullMethodName("MockMethod")
            .setType(MethodDescriptor.MethodType.UNARY)
            .setRequestMarshaller(marshaller)
            .setResponseMarshaller(marshaller)
            .build();
    when(call.getMethodDescriptor()).thenReturn(methodDescriptor);
    Attributes attributes =
        Attributes.newBuilder()
            .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress(8090))
            .build();
    when(call.getAttributes()).thenReturn(attributes);
    return call;
  }
}
