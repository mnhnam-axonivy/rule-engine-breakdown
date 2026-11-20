package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.RuleEngineConfig.Var;
import com.axonivy.utils.rule.engine.test.mock.MockRuntime;

import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;
import ch.ivyteam.ivy.process.call.SubProcessCallStartEvent;
import ch.ivyteam.ivy.process.call.SubProcessCallStartParamCaller;
import ch.ivyteam.ivy.process.call.SubProcessSearchFilter;
import ch.ivyteam.ivy.process.call.SubProcessSearchFilter.SearchScope;
import ch.ivyteam.ivy.security.exec.Sudo;

/**
 * The bridge to the tab "Rule Management" of the Axon Ivy Portal: the callable sub process Portal/RuleManagement,
 * called the way the Portal calls it. The Portal knows nothing of the rule engine but these signatures, so they
 * and the JDK types they answer with are a contract. The decision runtime is replaced by {@link MockRuntime}.
 */
@IvyProcessTest(enableWebServer = true)
@SuppressWarnings("unchecked")
class TestRuleManagement {

  private static final List<String> SIGNATURES = List.of(
      "ruleManagementInfo()",
      "ruleManagementModels()",
      "ruleManagementVersions(String)",
      "ruleManagementContent(String,Integer)");

  private static final String DMN = "<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\" name=\"discount\"/>";

  @BeforeEach
  void setup(AppFixture fixture) {
    MockRuntime.reset();
    fixture.var(Var.URL, MockRuntime.url());
    fixture.var(Var.API_KEY, "test-key");
    fixture.var(Var.MODEL_PREFIX, "");
  }

  @Test
  void portalFindsEveryCallInTheSecurityContext() {
    for (String signature : SIGNATURES) {
      assertThat(find(signature)).as(signature).hasSize(1);
    }
  }

  @Test
  void info() {
    Map<String, Object> info = (Map<String, Object>) result(call("ruleManagementInfo()", Map.of()));

    assertThat(info).containsEntry("runtimeUrl", MockRuntime.url()).containsEntry("reachable", true);
  }

  @Test
  void modelsOfTheRuntimeAndFilesOfTheProjects() {
    MockRuntime.model("discount", "discount", "purchaseAmount");

    List<Map<String, Object>> models = (List<Map<String, Object>>) result(call("ruleManagementModels()", Map.of()));

    assertThat(models).extracting(model -> model.get("name"))
        .as("sorted by name: the runtime's models and the .dmn files of rule-engine-test")
        .containsSubsequence("discount", "invoice-approval");
    Map<String, Object> discount = byName(models, "discount");
    assertThat(discount).containsEntry("onRuntime", true).containsEntry("activeVersion", 1)
        .containsEntry("versionCount", 1).containsEntry("decisions", List.of("discount"))
        .containsEntry("file", "discount.dmn");
    assertThat((String) discount.get("project")).startsWith("rule-engine-test");
    assertThat(byName(models, "invoice-approval")).containsEntry("onRuntime", false).containsEntry("versionCount", 0)
        .containsEntry("file", "invoice-approval.dmn");
    assertThat(MockRuntime.calls("models").get(0).apiKey()).isEqualTo("test-key");
  }

  @Test
  void modelsOfOtherApplicationsAreLeftOut(AppFixture fixture) {
    fixture.var(Var.MODEL_PREFIX, "crm-");
    MockRuntime.model("crm-pricing", "price", "amount");
    MockRuntime.model("hr-bonus", "bonus", "salary");

    List<Map<String, Object>> models = (List<Map<String, Object>>) result(call("ruleManagementModels()", Map.of()));

    assertThat(byName(models, "pricing")).containsEntry("onRuntime", true);
    assertThat(models).extracting(model -> model.get("name")).doesNotContain("hr-bonus", "bonus", "crm-pricing");
  }

  @Test
  void contentOfTheActiveVersion() {
    MockRuntime.model("discount", "discount", "purchaseAmount");
    MockRuntime.content("discount", 1, DMN);

    Map<String, Object> content = (Map<String, Object>) result(call("ruleManagementContent(String,Integer)",
        params("model", "discount", "version", null)));

    assertThat(content).containsEntry("model", "discount").containsEntry("version", 1).containsEntry("dmn", DMN);
  }

  @Test
  void aModelThatIsNotOnTheRuntimeIsReadFromItsProject() {
    Map<String, Object> content = (Map<String, Object>) result(call("ruleManagementContent(String,Integer)",
        params("model", "invoice-approval", "version", null)));

    assertThat(content).containsEntry("version", null);
    assertThat((String) content.get("project")).startsWith("rule-engine-test");
    assertThat((String) content.get("dmn")).contains("<definitions").contains("riskLevel");
  }

  @Test
  void versionsNewestFirst() {
    MockRuntime.versions("discount", """
        [ {"model":"discount","version":1,"status":"RETIRED","source":"PROJECT","author":"demo"},
          {"model":"discount","version":2,"status":"ACTIVE","source":"PORTAL","author":"admin","description":"Gold 20",
           "createdAt":"2026-11-17T10:00:00Z","activatedAt":"2026-11-17T10:01:00Z","activatedBy":"admin"} ]""");

    List<Map<String, Object>> versions = (List<Map<String, Object>>) result(call("ruleManagementVersions(String)",
        params("model", "discount")));

    assertThat(versions).extracting(version -> version.get("version")).containsExactly(2, 1);
    assertThat(versions.get(0)).containsEntry("status", "ACTIVE").containsEntry("source", "PORTAL")
        .containsEntry("description", "Gold 20").containsEntry("activatedBy", "admin")
        .containsEntry("activatedAt", "2026-11-17T10:01Z");
  }

  @Test
  void anUnreachableRuntimeIsAnError(AppFixture fixture) {
    fixture.var(Var.URL, "http://127.0.0.1:9");

    Map<String, Object> answer = call("ruleManagementModels()", Map.of());

    assertThat((Map<String, Object>) answer.get("error")).containsEntry("code", "UNREACHABLE");
    assertThat((Map<String, Object>) result(call("ruleManagementInfo()", Map.of()))).containsEntry("reachable", false);
  }

  /** Calls a start of the bridge as the Portal does: found by its signature in the security context. */
  private static Map<String, Object> call(String signature, Map<String, Object> params) {
    return Sudo.get(() -> {
      SubProcessCallStartEvent start = find(signature).get(0);
      if (params.isEmpty()) {
        return start.call().asMap();
      }
      SubProcessCallStartParamCaller caller = null;
      for (var param : params.entrySet()) {
        caller = (caller == null ? start : caller).withParam(param.getKey(), param.getValue());
      }
      return caller.call().asMap();
    });
  }

  private static List<SubProcessCallStartEvent> find(String signature) {
    return Sudo.get(() -> SubProcessCallStartEvent.find(SubProcessSearchFilter.create()
        .setSearchScope(SearchScope.SECURITY_CONTEXT)
        .setSignature(signature)
        .toFilter()));
  }

  private static Object result(Map<String, Object> answer) {
    assertThat(answer.get("error")).as("error of the call").isNull();
    return answer.get("result");
  }

  private static Map<String, Object> byName(List<Map<String, Object>> models, String name) {
    return models.stream().filter(model -> name.equals(model.get("name"))).findFirst()
        .orElseThrow(() -> new AssertionError("no model " + name + " in " + models));
  }

  private static Map<String, Object> params(Object... keysAndValues) {
    var params = new LinkedHashMap<String, Object>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      params.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return params;
  }
}
