package com.axonivy.portal.bean;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;

import com.axonivy.portal.components.util.FacesMessageUtils;
import com.axonivy.portal.rule.RuleEngineAnswer;
import com.axonivy.portal.rule.RuleEngineBridge;
import com.axonivy.portal.rule.RuleEngineError;
import com.axonivy.portal.rule.RuleModel;

import ch.ivy.addon.portalkit.util.PermissionUtils;
import ch.ivyteam.ivy.environment.Ivy;

/**
 * The tab "Rule Management" of the Admin Settings: the rules (DMN models) of the Axon Ivy Rules Engine. Everything
 * goes through {@link RuleEngineBridge}.
 */
@Named
@ViewScoped
public class RuleManagementBean implements Serializable {

  private static final long serialVersionUID = 1L;
  private static final String CMS = "/ch.ivy.addon.portalkit.ui.jsf/components/RuleManagement/";

  private String runtimeUrl;
  private boolean reachable;
  private String loadProblem;
  private List<RuleModel> models = new ArrayList<>();
  private List<RuleModel> filteredModels;

  @PostConstruct
  public void init() {
    load();
  }

  /** Reads the state of the runtime and its models again. */
  public void refresh() {
    load();
  }

  private void load() {
    if (!allowed()) {
      return;
    }
    RuleEngineAnswer info = RuleEngineBridge.info();
    runtimeUrl = info.isOk() ? (String) info.map().get("runtimeUrl") : null;
    reachable = info.isOk() && Boolean.TRUE.equals(info.map().get("reachable"));
    RuleEngineAnswer answer = RuleEngineBridge.models();
    if (answer.isOk()) {
      models = answer.list().stream().map(RuleModel::of).toList();
      loadProblem = null;
    } else {
      models = List.of();
      loadProblem = describe(answer.error());
    }
    filteredModels = null;
  }

  // ── what the page shows ───────────────────────────────────────────────────────

  /** EL sees only the components of a record, so derived values come from here. */
  public String decisionNames(RuleModel model) {
    return String.join(", ", model.decisions());
  }

  public boolean isNotConfigured() {
    return StringUtils.isBlank(runtimeUrl);
  }

  // ── helpers ───────────────────────────────────────────────────────────────────

  /** Admin Settings are for administrators; the bean checks it again, the tab is not the only way in. */
  private static boolean allowed() {
    if (PermissionUtils.isSessionUserHasAdminRole()) {
      return true;
    }
    FacesContext.getCurrentInstance().addMessage(null, FacesMessageUtils.sanitizedMessage(FacesMessage.SEVERITY_ERROR,
        Ivy.cms().co("/ch.ivy.addon.portalkit.ui.jsf/permission/noPermission"), null));
    return false;
  }

  private String describe(RuleEngineError error) {
    if (RuleEngineError.NOT_CONFIGURED.equals(error.code())) {
      return cms("Messages/NotConfigured");
    }
    if (error.isConnectionProblem()) {
      return cms("Messages/Unreachable", StringUtils.defaultString(runtimeUrl));
    }
    return error.message();
  }

  private static String cms(String key, Object... params) {
    return params.length == 0 ? Ivy.cms().co(CMS + key) : Ivy.cms().co(CMS + key, List.of(params));
  }

  // ── getters and setters ───────────────────────────────────────────────────────

  public String getRuntimeUrl() {
    return runtimeUrl;
  }

  public boolean isReachable() {
    return reachable;
  }

  public String getLoadProblem() {
    return loadProblem;
  }

  public List<RuleModel> getModels() {
    return models;
  }

  public List<RuleModel> getFilteredModels() {
    return filteredModels;
  }

  public void setFilteredModels(List<RuleModel> filteredModels) {
    this.filteredModels = filteredModels;
  }
}
