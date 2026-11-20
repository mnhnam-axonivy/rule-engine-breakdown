package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.RuleEngineConfig.Var;
import com.axonivy.utils.rule.engine.api.VersionStatus;
import com.axonivy.utils.rule.engine.deploy.ModelDeployer;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.test.mock.MockRuntime;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;

/**
 * The Java API for a Rules UI: every call goes to the runtime of the variables RuleEngine.*. The runtime is
 * replaced by {@link MockRuntime}.
 */
@IvyProcessTest(enableWebServer = true)
class TestRuleEngineApi {

  private Path exported;

  @BeforeEach
  public void setup(AppFixture fixture) {
    MockRuntime.reset();
    ModelDeployer.reset();
    fixture.var(Var.URL, MockRuntime.url());
    fixture.var(Var.API_KEY, "test-key");
    fixture.var(Var.MODEL_PREFIX, "");
    exported = Project.current().directory().resolve("dmn").resolve("exported.dmn");
  }

  @AfterEach
  public void cleanup() throws IOException {
    Files.deleteIfExists(exported);
    ProjectDmnFiles.refresh();
  }

  @Test
  void evaluate_asksTheActiveVersion_ofTheModelWithItsPrefix(AppFixture fixture) {
    fixture.var(Var.MODEL_PREFIX, "sales-");
    MockRuntime.onEvaluate(call -> MockRuntime.results(3, "{\"discount\":15}"));

    var response = RuleEngine.evaluate("discount", "discount", Map.of("purchaseAmount", 1500));

    assertThat(response.version()).isEqualTo(3);
    assertThat(response.results()).containsEntry("discount", 15);
    var call = MockRuntime.calls("evaluate").get(0);
    assertThat(call.model()).isEqualTo("sales-discount");
    assertThat(call.apiKey()).isEqualTo("test-key");
    assertThat(call.body().get("decision").asString()).isEqualTo("discount");
  }

  @Test
  void register_storesADraftFromThePortal() {
    RuleEngine.register("discount", "<definitions/>", "rule.editor", "Gold discount raised");

    var body = MockRuntime.calls("register").get(0).body();
    assertThat(body.get("content").asString()).isEqualTo("<definitions/>");
    assertThat(body.get("author").asString()).isEqualTo("rule.editor");
    assertThat(body.get("description").asString()).isEqualTo("Gold discount raised");
    assertThat(body.get("source").asString()).isEqualTo("PORTAL");
    assertThat(body.get("activate").asBoolean()).as("it goes live only when it is activated").isFalse();
  }

  @Test
  void activate_nowOrAtADate_andRollBack() {
    RuleEngine.activate("discount", 2, "rule.approver");
    var effective = OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(1));
    RuleEngine.activate("discount", 3, "rule.approver", effective);
    MockRuntime.onRollback(call -> MockRuntime.json(200,
        "{\"model\":\"discount\",\"version\":2,\"status\":\"ACTIVE\",\"source\":\"PORTAL\"}"));
    var back = RuleEngine.rollback("discount", "rule.approver");

    var activations = MockRuntime.calls("activate");
    assertThat(activations.get(0).body().get("version").asInt()).isEqualTo(2);
    assertThat(activations.get(0).body().get("user").asString()).isEqualTo("rule.approver");
    assertThat(activations.get(0).body().has("effectiveDate")).as("now").isFalse();
    assertThat(activations.get(1).body().get("effectiveDate").asString()).startsWith("2027-01-01T00:00");
    assertThat(back.version()).isEqualTo(2);
    assertThat(MockRuntime.calls("rollback").get(0).body().get("user").asString()).isEqualTo("rule.approver");
  }

  @Test
  void versions_newestFirst() {
    MockRuntime.versions("discount", """
        [{"model":"discount","version":2,"status":"ACTIVE","source":"PORTAL"},
         {"model":"discount","version":1,"status":"RETIRED","source":"PROJECT"}]
        """);

    var versions = RuleEngine.versions("discount");

    assertThat(versions).extracting(version -> version.version()).containsExactly(2, 1);
    assertThat(versions.get(0).status()).isEqualTo(VersionStatus.ACTIVE);
  }

  @Test
  void deploy_registersEveryDmnFileOfTheProject() {
    var versions = RuleEngine.deploy(Project.current());

    assertThat(versions).isNotEmpty();
    assertThat(MockRuntime.calls("register")).extracting(MockRuntime.Call::model)
        .contains("discount", "invoice-approval");
    assertThat(MockRuntime.calls("register"))
        .allMatch(call -> call.body().get("source").asString().equals("PROJECT"));
  }

  @Test
  void export_writesTheVersionIntoTheDmnFolderOfTheProject() throws IOException {
    MockRuntime.content("exported", 4, "<definitions name=\"exported\"/>");

    Path file = RuleEngine.export("exported", 4, Project.current());

    assertThat(file).isEqualTo(exported);
    assertThat(Files.readString(file)).isEqualTo("<definitions name=\"exported\"/>");
  }
}
