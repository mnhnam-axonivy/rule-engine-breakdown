package com.axonivy.portal.rule;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;

import com.axonivy.portal.components.service.IvyAdapterService;

import ch.ivyteam.ivy.environment.Ivy;
import ch.ivyteam.ivy.process.call.SubProcessCallStartEvent;
import ch.ivyteam.ivy.process.call.SubProcessSearchFilter;
import ch.ivyteam.ivy.process.call.SubProcessSearchFilter.SearchScope;
import ch.ivyteam.ivy.security.exec.Sudo;

/**
 * The connection of the Portal to the Axon Ivy Rules Engine, without a dependency on it.
 *
 * <p>The rule engine project offers the callable sub process <code>Portal/RuleManagement</code>. Like Smart Workflow,
 * it is found by its signatures in the security context: without it the Portal shows no Rule Management and works as
 * before. Only JDK types cross, so the Portal needs no class of the rule engine.</p>
 *
 * <p>Every call answers with a result and an error, see {@link RuleEngineAnswer}.</p>
 */
public final class RuleEngineBridge {

  static final String INFO = "ruleManagementInfo()";
  static final String MODELS = "ruleManagementModels()";
  static final String VERSIONS = "ruleManagementVersions(String)";
  static final String CONTENT = "ruleManagementContent(String,Integer)";
  static final String VALIDATE = "ruleManagementValidate(String)";
  static final String SAVE = "ruleManagementSave(String,String,String,String,Boolean)";
  static final String ACTIVATE = "ruleManagementActivate(String,Integer,String)";
  static final String ROLLBACK = "ruleManagementRollback(String,String)";

  private RuleEngineBridge() {}

  /** @return true if a rule engine project is in the security context */
  public static boolean isAvailable() {
    try {
      return Sudo.get(() -> CollectionUtils.isNotEmpty(SubProcessCallStartEvent.find(SubProcessSearchFilter.create()
          .setSearchScope(SearchScope.SECURITY_CONTEXT)
          .setSignature(MODELS)
          .toFilter())));
    } catch (Exception ex) {
      Ivy.log().warn("Rule management: cannot look up the rule engine", ex);
      return false;
    }
  }

  /** runtimeUrl, reachable, editorScript and modelPrefix */
  public static RuleEngineAnswer info() {
    return call(INFO, null);
  }

  private static RuleEngineAnswer call(String signature, Map<String, Object> params) {
    try {
      Map<String, Object> response = IvyAdapterService.startSubProcessInSecurityContext(signature, params);
      if (response == null) {
        return RuleEngineAnswer.failure(RuleEngineError.NOT_AVAILABLE,
            "There is no rule engine project in this security context.");
      }
      return new RuleEngineAnswer(response.get("result"), RuleEngineError.of(response.get("error")));
    } catch (Exception ex) {
      Ivy.log().error("Rule management: the call " + signature + " failed", ex);
      return RuleEngineAnswer.failure(RuleEngineError.FAILED, ex.getMessage());
    }
  }

  private static Map<String, Object> params(Object... namesAndValues) {
    var params = new LinkedHashMap<String, Object>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      params.put((String) namesAndValues[i], namesAndValues[i + 1]);
    }
    return params;
  }
}
