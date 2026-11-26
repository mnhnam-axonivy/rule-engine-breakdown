package com.axonivy.utils.rule.engine;

import com.axonivy.utils.rule.engine.client.RuleEngineClient;

/**
 * Java API of the Axon Ivy Rules Engine. Everything goes to the decision runtime that is configured in the
 * variables <code>RuleEngine.*</code>.
 */
public final class RuleEngine {

  private RuleEngine() {}

  /** @return a client of the decision runtime, configured by the variables <code>RuleEngine.*</code> */
  public static RuleEngineClient client() {
    return client(RuleEngineConfig.fromVariables());
  }

  public static RuleEngineClient client(RuleEngineConfig config) {
    if (config.url().isBlank()) {
      throw new IllegalStateException("The URL of the rule engine is not configured. Set the variable "
          + RuleEngineConfig.Var.URL + ".");
    }
    return RuleEngineClient.builder()
        .baseUrl(config.url())
        .apiKey(config.apiKey())
        .connectTimeout(config.connectTimeout())
        .readTimeout(config.timeout())
        .logRequests(config.logRequests())
        .logResponses(config.logRequests())
        .build();
  }
}
