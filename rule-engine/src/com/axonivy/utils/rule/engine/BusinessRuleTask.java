package com.axonivy.utils.rule.engine;

import java.net.URI;

import com.axonivy.utils.rule.engine.program.internal.RuleTaskEditor;
import com.axonivy.utils.rule.engine.program.internal.RuleTaskExecutor;

import ch.ivyteam.ivy.process.program.activity.AbortableExecution;
import ch.ivyteam.ivy.process.program.activity.ProgramExecutor;
import ch.ivyteam.ivy.process.program.element.ProgramIconDecorator;
import ch.ivyteam.ivy.process.program.exec.ProgramContext;
import ch.ivyteam.ivy.process.program.ui.ProgramEditorUi;
import ch.ivyteam.ivy.process.program.ui.ProgramUiBuilder;

/**
 * Business Rule Task: evaluates a DMN decision with process data, without code.
 * <p>The decision is evaluated by the decision runtime of the Axon Ivy Rules Engine. Inputs and results are
 * mapped by name. Runtime not reachable, DMN errors and timeouts raise BPMN errors.</p>
 */
public class BusinessRuleTask implements ProgramExecutor, ProgramEditorUi, ProgramIconDecorator {

  @Override
  public AbortableExecution newExecution() {
    return new AbortableExecution() {

      private volatile Thread worker;

      @Override
      public void execute(ProgramContext context) {
        worker = Thread.currentThread();
        try {
          new RuleTaskExecutor(context).execute();
        } finally {
          worker = null;
        }
      }

      @Override
      public void abort() {
        Thread running = worker;
        if (running != null) {
          running.interrupt();
        }
      }
    };
  }

  @Override
  public void editor(ProgramUiBuilder ui) {
    new RuleTaskEditor().editor(ui);
  }

  @Override
  public URI icon() {
    return URI.create("res:/webContent/logo/business-rule-task.png");
  }
}
