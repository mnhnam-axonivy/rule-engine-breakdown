package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.axonivy.utils.rule.engine.RuleEngineConfig.Var;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.demo.InvoiceSamples;
import com.axonivy.utils.rule.engine.deploy.ModelDeployer;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.program.internal.RuleErrors;

import ch.ivyteam.ivy.bpm.engine.client.BpmClient;
import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;

/**
 * End to end against the real decision runtime in a container: Ivy process, Business Rule Task, REST,
 * DMN engine and the database of the runtime.
 * <p>Build the image first: <code>docker compose build</code>, then <code>mvn verify -Pcontainer.test</code>.</p>
 */
@IvyProcessTest
class RuleEngineContainerTest {

  private static final String API_KEY = "container-secret";

  @SuppressWarnings("resource")
  static GenericContainer<?> runtime = new GenericContainer<>(
      System.getProperty("rule.engine.image", "axonivy/rule-engine-runtime:dev"))
      .withEnv("RULE_ENGINE_API_KEY", API_KEY)
      .withExposedPorts(8080)
      .waitingFor(Wait.forHttp("/q/health/ready").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

  @BeforeAll
  public static void startRuntime() {
    runtime.start();
  }

  @AfterAll
  public static void stopRuntime() {
    runtime.stop();
  }

  @BeforeEach
  public void setup(AppFixture fixture) {
    fixture.var(Var.URL, "http://" + runtime.getHost() + ":" + runtime.getMappedPort(8080));
    fixture.var(Var.API_KEY, API_KEY);
    fixture.var(Var.AUTO_DEPLOY, "true");
    fixture.var(Var.MODEL_PREFIX, "");
    ModelDeployer.reset();
    ProjectDmnFiles.refresh();
  }

  @Test
  void invalidDmn_isRejectedWithReadableMessage() {
    assertThatThrownBy(() -> RuleEngine.register("broken", "<definitions>this is not DMN</definitions>", "me", ""))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.INVALID_MODEL);
          assertThat(ex.getMessage()).contains("not a DMN model");
        });
  }

  @Test
  void wrongApiKey_isAnsweredByTheDemo(BpmClient client, AppFixture fixture) {
    fixture.var(Var.API_KEY, "wrong");

    var result = TestInvoicePricing.execute(client, InvoiceSamples.standard()).subResult();

    assertThat(result.param("errorCode", String.class)).isEqualTo(RuleErrors.DMN);
    assertThat(result.param("errorMessage", String.class)).contains("API key");
  }

  @Test
  void runtimeIsReady() {
    assertThat(RuleEngine.client().isReady()).isTrue();
  }
}
