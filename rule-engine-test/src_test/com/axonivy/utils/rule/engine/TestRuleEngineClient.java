package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.client.http.HttpClientBuilderLoader;
import com.axonivy.utils.rule.engine.client.http.HttpMethod;
import com.axonivy.utils.rule.engine.client.http.HttpRequest;
import com.axonivy.utils.rule.engine.client.http.ivy.IvyHttpClientBuilder;
import com.axonivy.utils.rule.engine.client.http.log.LoggingHttpClient;

import ch.ivyteam.ivy.environment.IvyTest;

/**
 * The transport of the client of the decision runtime.
 */
@IvyTest
class TestRuleEngineClient {

  @Test
  void ivyRestClient_isTheDefaultTransport() {
    assertThat(HttpClientBuilderLoader.loadHttpClientBuilder())
        .as("the Ivy REST client integrates into the tracing tools of the engine")
        .isInstanceOf(IvyHttpClientBuilder.class);
  }

  @Test
  void secretsAreMaskedInLogs() {
    var request = HttpRequest.builder().method(HttpMethod.GET).url("http://rules:8080", "api/models")
        .addHeader("X-API-Key", "secret").addHeader("Accept", "application/json").build();
    assertThat(LoggingHttpClient.format(request.headers()))
        .contains("[X-API-Key: ***]")
        .contains("[Accept: application/json]")
        .doesNotContain("secret");
  }
}
