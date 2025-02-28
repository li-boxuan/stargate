/*
 * Copyright The Stargate Authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.stargate.it.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.stargate.it.storage.StargateSpec;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.jcip.annotations.NotThreadSafe;
import okhttp3.*;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests from Stargate V1 that exercise refactored REST API metrics; refactored from earlier
 * monolithic {@code MetricsTest}.
 */
@NotThreadSafe
@StargateSpec()
@ExtendWith(ApiServiceExtension.class)
@ApiServiceSpec(parametersCustomizer = "buildApiServiceParameters")
public class RestApiMetricsTest extends BaseRestApiTest {

  private static String restUrlBase;
  private static String metricsUrlBase;

  @SuppressWarnings("unused") // referenced in @StargateSpec
  public static void buildApiServiceParameters(ApiServiceParameters.Builder builder) {
    BaseRestApiTest.buildApiServiceParameters(builder);
    // These are still needed:
    builder.putSystemProperties("stargate.metrics.http_server_requests_percentiles", "0.95,0.99");
    builder.putSystemProperties(
        "stargate.metrics.http_server_requests_path_param_tags", "keyspaceName");
  }

  @BeforeAll
  public static void setup(ApiServiceConnectionInfo service) {
    restUrlBase = "http://" + service.host() + ":" + service.port();
    metricsUrlBase = "http://" + service.host() + ":" + service.metricsPort();
  }

  // 13-Jan-2022, tatu: In StargateV1 HTTP Tag Provider is registered using OSGi.
  //    SGv2 does not have equivalent mechanism implemented yet, so tag functionality
  //    NOT verified currently.
  @Test
  public void restApiHttpRequestMetrics() throws IOException {
    final String TEST_UUID = "2f689a6f-82d7-4728-b956-b0dff00eda23";

    // call the rest api path with target header
    String path = String.format("%s/v2/schemas/keyspaces", restUrlBase);
    OkHttpClient client = new OkHttpClient().newBuilder().build();
    Request request =
        new Request.Builder()
            .url(path)
            .get()
            // This should become "tenant" tag
            .addHeader("Host", TEST_UUID)
            .build();

    int status = execute(client, request);

    await()
        .atMost(Duration.ofSeconds(5))
        // to reduce noise, do NOT call back-to-back but once per second:
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              String result =
                  RestUtils.get("", String.format("%s/metrics", metricsUrlBase), HttpStatus.SC_OK);

              // metered http request lines
              List<String> meterLines =
                  Arrays.stream(result.split(System.getProperty("line.separator")))
                      .filter(line -> line.startsWith("http_server_requests_seconds"))
                      .collect(Collectors.toList());

              assertThat(meterLines)
                  .anySatisfy(
                      metric ->
                          assertThat(metric)
                              .contains("method=\"GET\"")
                              // NOTE! Hyphens remain and are NOT converted to underscores
                              .contains("module=\"sgv2-restapi\"")
                              .contains("uri=\"/v2/schemas/keyspaces\"")
                              .contains(String.format("status=\"%d\"", status))
                              .contains("tenant=\"" + TEST_UUID + "\"")
                              .contains("quantile=\"0.95\"")
                              .doesNotContain("error"))
                  .anySatisfy(
                      metric ->
                          assertThat(metric)
                              .contains("method=\"GET\"")
                              .contains("module=\"sgv2-restapi\"")
                              .contains("uri=\"/v2/schemas/keyspaces\"")
                              .contains(String.format("status=\"%d\"", status))
                              .contains("tenant=\"" + TEST_UUID + "\"")
                              .contains("quantile=\"0.99\"")
                              .doesNotContain("error"));

              // counted http request lines
              List<String> counterLines =
                  Arrays.stream(result.split(System.getProperty("line.separator")))
                      .filter(line -> line.startsWith("http_server_requests_counter"))
                      .collect(Collectors.toList());

              assertThat(counterLines)
                  .anySatisfy(
                      metric ->
                          assertThat(metric)
                              .contains("error=\"false\"")
                              .contains("module=\"sgv2-restapi\"")
                              .doesNotContain("method=\"GET\"")
                              .doesNotContain("uri=\"/v2/schemas/keyspaces\"")
                              .doesNotContain(String.format("status=\"%d\"", status))
                              .contains("tenant=\"" + TEST_UUID + "\""));
            });
  }

  // from [#1660] (PR [#1662]) ("non-api endpoint metrics to be reported with different")
  @Test
  public void restNonApiHttpRequestMetrics() throws IOException {
    final String TEST_UUID = "4ded57a2-5324-4e82-a881-d9d777672a91";

    // call the rest api path with target header
    String path = String.format("%s", metricsUrlBase);
    OkHttpClient client = new OkHttpClient().newBuilder().build();
    Request request =
        new Request.Builder()
            .url(path)
            .get()
            // This should become "tenant" tag (note: here we pass lower-case)
            .addHeader("host", TEST_UUID)
            .build();

    int status = execute(client, request);

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              String result =
                  RestUtils.get("", String.format("%s/metrics", metricsUrlBase), HttpStatus.SC_OK);

              // metered http request lines
              List<String> meterLines =
                  Arrays.stream(result.split(System.getProperty("line.separator")))
                      .filter(line -> line.startsWith("http_server_requests_seconds"))
                      .collect(Collectors.toList());

              assertThat(meterLines)
                  .anySatisfy(
                      metric ->
                          assertThat(metric)
                              .contains("method=\"GET\"")
                              .contains("module=\"sgv2-restapi-other\"")
                              .contains("uri=\"root\"")
                              .contains(String.format("status=\"%d\"", status))
                              .contains("tenant=\"" + TEST_UUID + "\"")
                              .contains("quantile=\"0.95\"")
                              .doesNotContain("error"))
                  .anySatisfy(
                      metric ->
                          assertThat(metric)
                              .contains("method=\"GET\"")
                              .contains("module=\"sgv2-restapi-other\"")
                              .contains("uri=\"root\"")
                              .contains(String.format("status=\"%d\"", status))
                              .contains("tenant=\"" + TEST_UUID + "\"")
                              .contains("quantile=\"0.99\""))
                  .doesNotContain("error");

              // counted http request lines
              List<String> counterLines =
                  Arrays.stream(result.split(System.getProperty("line.separator")))
                      .filter(line -> line.startsWith("http_server_requests_counter"))
                      .collect(Collectors.toList());

              assertThat(counterLines)
                  .anySatisfy(
                      metric ->
                          assertThat(metric)
                              .contains("error=\"false\"")
                              .contains("module=\"sgv2-restapi-other\"")
                              .doesNotContain("method=\"GET\"")
                              .doesNotContain("uri=\"root\"")
                              .doesNotContain(String.format("status=\"%d\"", status))
                              .contains("tenant=\"" + TEST_UUID + "\""));
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"sgv2_restapi"})
  public void dropwizardMetricsModule(String module) throws IOException {
    String[] expectedMetricGroups =
        new String[] {"TimeBoundHealthCheck", "io_dropwizard_jersey", "org_eclipse_jetty"};

    String result =
        RestUtils.get("", String.format("%s/metrics", metricsUrlBase), HttpStatus.SC_OK);

    List<String> moduleLines =
        Arrays.stream(result.split(System.getProperty("line.separator")))
            .filter(line -> line.startsWith(module))
            .collect(Collectors.toList());

    for (String metricGroup : expectedMetricGroups) {
      assertThat(moduleLines).anyMatch(line -> line.contains(metricGroup));
    }
  }

  private int execute(OkHttpClient client, Request request) throws IOException {
    try (Response execute = client.newCall(request).execute()) {
      assertThat(execute.body()).isNotNull();
      assertThat(execute.code()).isNotZero();
      return execute.code();
    }
  }
}
