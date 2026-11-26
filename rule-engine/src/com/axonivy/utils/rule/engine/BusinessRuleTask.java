package com.axonivy.utils.rule.engine;

import java.net.URI;

import com.axonivy.utils.rule.engine.program.internal.RuleTaskEditor;

import ch.ivyteam.ivy.process.program.element.ProgramIconDecorator;
import ch.ivyteam.ivy.process.program.ui.ProgramEditorUi;
import ch.ivyteam.ivy.process.program.ui.ProgramUiBuilder;

/**
 * Business Rule Task: evaluates a DMN decision with process data, without code.
 * <p>This is the element and its inscription mask: the decision of a .dmn file of the project is selected here.</p>
 */
public class BusinessRuleTask implements ProgramEditorUi, ProgramIconDecorator {

  @Override
  public void editor(ProgramUiBuilder ui) {
    new RuleTaskEditor().editor(ui);
  }

  @Override
  public URI icon() {
    return URI.create("res:/webContent/logo/business-rule-task.png");
  }
}
