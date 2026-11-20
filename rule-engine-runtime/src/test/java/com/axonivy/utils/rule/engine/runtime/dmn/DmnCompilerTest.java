package com.axonivy.utils.rule.engine.runtime.dmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.api.Message;
import com.axonivy.utils.rule.engine.api.ModelInfo;
import com.axonivy.utils.rule.engine.runtime.TestModels;

/**
 * A DMN file becomes an executable model in memory: no code generation, no build, no restart.
 */
class DmnCompilerTest {

  private final DmnCompiler compiler = new DmnCompiler();
  private final DmnEvaluator evaluator = new DmnEvaluator();

  @Test
  void compiledModel_knowsItsDecisionsAndInputs() {
    var model = compiler.compile("discount", 1, TestModels.discount(15));

    assertThat(model.decisions()).extracting(ModelInfo.Decision::name).containsExactly("discount");
    assertThat(model.decisions().get(0).requiredInputs()).containsExactlyInAnyOrder("purchaseAmount", "type");
    assertThat(model.inputs()).extracting(ModelInfo.Input::name).containsExactlyInAnyOrder("purchaseAmount", "type");
    assertThat(model.hasDecision("discount")).isTrue();
  }

  @Test
  void namedDecision_isEvaluated() {
    var model = compiler.compile("discount", 1, TestModels.discount(15));

    var gold = evaluator.evaluate(model, "discount", Map.of("purchaseAmount", 1200, "type", "Gold"));
    var silver = evaluator.evaluate(model, "discount", Map.of("purchaseAmount", 1200, "type", "Silver"));

    assertThat(gold.hasErrors()).isFalse();
    assertThat(number(gold.results().get("discount"))).isEqualByComparingTo("15");
    assertThat(number(silver.results().get("discount"))).isEqualByComparingTo("0");
  }

  @Test
  void withoutDecision_everyDecisionOfTheModelIsEvaluated() {
    var model = compiler.compile("discount", 1, TestModels.discount(15));
    var all = evaluator.evaluate(model, null, Map.of("purchaseAmount", 10, "type", "Gold"));
    assertThat(all.results()).containsOnlyKeys("discount");
  }

  @Test
  void textInput_becomesADate_whereTheModelAsksForOne() {
    var model = compiler.compile("dueDate", 1, TestModels.dueDate());
    var due = evaluator.evaluate(model, "dueDate", Map.of("invoiceDate", "2026-01-01"));
    assertThat(String.valueOf(due.results().get("dueDate"))).isEqualTo("2026-01-31");
  }

  @Test
  void valueOutsideOfTheAllowedValues_failsTheEvaluation_insteadOfMatchingNoRule() {
    var model = compiler.compile("tier", 1, TIER);
    assertThat(evaluator.evaluate(model, "label", Map.of("tier", "Gold")).hasErrors()).isFalse();
    assertThat(evaluator.evaluate(model, "label", Map.of("tier", "Bronze")).hasErrors()).as("type check is on").isTrue();
  }

  /** A string input with allowed values, like an enum of a data class. */
  private static final String TIER = """
      <?xml version="1.0" encoding="UTF-8"?>
      <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="_tier" name="tier"
          namespace="https://axonivy.com/dmn/tier"
          expressionLanguage="https://www.omg.org/spec/DMN/20230324/FEEL/"
          typeLanguage="https://www.omg.org/spec/DMN/20230324/FEEL/">
        <itemDefinition id="_tTier" name="tTier">
          <typeRef>string</typeRef>
          <allowedValues id="_tTier_values"><text>"Gold","Silver"</text></allowedValues>
        </itemDefinition>
        <inputData id="_in_tier" name="tier">
          <variable id="_in_tier_var" name="tier" typeRef="tTier"/>
        </inputData>
        <decision id="_d" name="label">
          <variable id="_d_var" name="label" typeRef="string"/>
          <informationRequirement id="_req1">
            <requiredInput href="#_in_tier"/>
          </informationRequirement>
          <literalExpression id="_le"><text>"Tier " + tier</text></literalExpression>
        </decision>
      </definitions>
      """;

  @Test
  void brokenModel_isRefusedWithReadableMessages() {
    assertThatThrownBy(() -> compiler.compile("discount", 1, TestModels.brokenExpression()))
        .isInstanceOf(InvalidModelException.class)
        .satisfies(ex -> assertThat(((InvalidModelException) ex).messages()).isNotEmpty().allMatch(Message::isError));
  }

  @Test
  void validation_findsWhatIsWrong() {
    assertThat(compiler.validate("")).extracting(Message::message).containsExactly("The DMN content is empty.");
    assertThat(compiler.validate("<invoice/>")).anyMatch(Message::isError);
    assertThat(compiler.validate(TestModels.brokenExpression())).anyMatch(Message::isError);
    assertThat(compiler.validate(TestModels.missingInput())).anyMatch(Message::isError);
    assertThat(compiler.validate(TestModels.discount(15))).noneMatch(Message::isError);
  }

  private static BigDecimal number(Object value) {
    return new BigDecimal(String.valueOf(value));
  }
}
