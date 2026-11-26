package com.axonivy.portal.bean;

import java.io.Serializable;

import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;

import com.axonivy.portal.components.util.FacesMessageUtils;
import com.axonivy.portal.rule.RuleEngineAnswer;
import com.axonivy.portal.rule.RuleEngineBridge;

import ch.ivy.addon.portalkit.util.PermissionUtils;
import ch.ivyteam.ivy.environment.Ivy;

/**
 * The tab "Rule Management" of the Admin Settings: the decision runtime of the Axon Ivy Rules Engine and whether it
 * answers. Everything goes through {@link RuleEngineBridge}.
 */
@Named
@ViewScoped
public class RuleManagementBean implements Serializable {

  private static final long serialVersionUID = 1L;

  private String runtimeUrl;
  private boolean reachable;

  @PostConstruct
  public void init() {
    load();
  }

  /** Reads the state of the runtime again. */
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
  }

  // ── what the page shows ───────────────────────────────────────────────────────

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

  // ── getters and setters ───────────────────────────────────────────────────────

  public String getRuntimeUrl() {
    return runtimeUrl;
  }

  public boolean isReachable() {
    return reachable;
  }
}
