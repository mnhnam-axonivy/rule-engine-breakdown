package com.axonivy.utils.rule.engine.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.runtime.api.model.VersionSource;
import com.axonivy.utils.rule.engine.runtime.TestModels;
import com.axonivy.utils.rule.engine.runtime.dmn.InvalidModelException;
import com.axonivy.utils.rule.engine.runtime.service.RuleEngineException.Code;
import com.axonivy.utils.rule.engine.runtime.service.VersionService.RegisterCommand;
import com.axonivy.utils.rule.engine.runtime.service.VersionStore.NewVersion;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * The active version of a model answers at once: a new version is live with the next evaluation, without a restart.
 */
@QuarkusTest
class VersionServiceTest {

  private static final Map<String, Object> GOLD = Map.of("purchaseAmount", 1200, "type", "Gold");

  @Inject
  VersionService service;

  @Inject
  VersionStore store;

  @Inject
  ModelRegistry registry;

  @Test
  void activatedVersion_answersTheNextEvaluation() {
    var model = TestModels.uniqueName("live");
    service.register(model, command(TestModels.discount(10), null));
    assertThat(evaluate(model)).isEqualTo(new Answer(1, "10"));

    service.register(model, command(TestModels.discount(15), true));
    assertThat(evaluate(model)).isEqualTo(new Answer(2, "15"));
  }

  @Test
  void sameFile_withCrlfOrLf_isOneVersion() {
    var model = TestModels.uniqueName("crlf");
    var lf = service.register(model, command(TestModels.discount(10), null));
    var crlf = service.register(model, command(TestModels.discount(10).replace("\n", "\r\n"), null));

    assertThat(lf.created()).isTrue();
    assertThat(crlf.created()).isFalse();
    assertThat(crlf.version().version).isEqualTo(lf.version().version);
  }

  @Test
  void draft_canBeEvaluated_beforeItGoesLive() {
    var model = TestModels.uniqueName("draft");
    service.register(model, command(TestModels.discount(10), null));
    service.register(model, command(TestModels.discount(15), false));

    var draft = service.evaluate(model, 2, "discount", GOLD);
    assertThat(draft.model().version()).isEqualTo(2);
    assertThat(number(draft.evaluation().results().get("discount"))).isEqualByComparingTo("15");
    assertThat(evaluate(model)).as("the active version is unchanged").isEqualTo(new Answer(1, "10"));
  }

  @Test
  void versionWithAnEffectiveDate_goesLiveWithTheScheduler() throws InterruptedException {
    var model = TestModels.uniqueName("effective");
    service.register(model, command(TestModels.discount(10), null));
    service.register(model, command(TestModels.discount(15), false));

    service.activate(model, 2, "approver", OffsetDateTime.now().plusSeconds(2));
    assertThat(evaluate(model).version()).isEqualTo(1);

    long until = System.currentTimeMillis() + 10_000;
    while (evaluate(model).version() != 2 && System.currentTimeMillis() < until) {
      Thread.sleep(200);
    }
    assertThat(evaluate(model)).isEqualTo(new Answer(2, "15"));
  }

  @Test
  void rollback_bringsThePreviousVersionBack_atOnce() {
    var model = TestModels.uniqueName("rollback");
    service.register(model, command(TestModels.discount(10), null));
    service.register(model, command(TestModels.discount(15), true));

    service.rollback(model, "admin");
    assertThat(evaluate(model)).isEqualTo(new Answer(1, "10"));
  }

  @Test
  void unknownDecision_namesTheDecisionsOfTheModel() {
    var model = TestModels.uniqueName("decision");
    service.register(model, command(TestModels.discount(10), null));

    assertThatThrownBy(() -> service.evaluate(model, "bonus", GOLD))
        .isInstanceOf(RuleEngineException.class)
        .hasMessageContaining("[discount]")
        .extracting(ex -> ((RuleEngineException) ex).code()).isEqualTo(Code.DECISION_NOT_FOUND);
  }

  @Test
  void modelWithoutActiveVersion_orUnknownModel_isAnsweredWithItsCode() {
    var model = TestModels.uniqueName("inactive");
    service.register(model, command(TestModels.discount(10), false));

    assertCode(() -> service.evaluate(model, "discount", GOLD), Code.NO_ACTIVE_VERSION);
    assertCode(() -> service.evaluate(TestModels.uniqueName("unknown"), "discount", GOLD), Code.MODEL_NOT_FOUND);
    assertCode(() -> service.register("no spaces allowed", command(TestModels.discount(10), null)), Code.INVALID_REQUEST);
  }

  @Test
  void invalidDmn_isNotStored() {
    var model = TestModels.uniqueName("invalid");
    assertThatThrownBy(() -> service.register(model, command(TestModels.brokenExpression(), null)))
        .isInstanceOf(InvalidModelException.class);
    assertThat(store.exists(model)).isFalse();
  }

  @Test
  void storedVersionThatCannotBeLoaded_failsOnlyItsOwnModel() {
    var broken = TestModels.uniqueName("broken");
    var healthy = TestModels.uniqueName("healthy");
    service.register(healthy, command(TestModels.discount(10), null));
    // written to the store directly, as an older runtime could have done it
    store.register(new NewVersion(broken, TestModels.brokenExpression(), "broken", "discount", "ns", "test", null,
        VersionSource.API, true, null));
    registry.sync();

    assertThatThrownBy(() -> service.evaluate(broken, "discount", GOLD))
        .isInstanceOf(RuleEngineException.class)
        .hasMessageContaining("cannot be loaded")
        .extracting(ex -> ((RuleEngineException) ex).code()).isEqualTo(Code.INTERNAL_ERROR);
    assertThat(evaluate(healthy)).isEqualTo(new Answer(1, "10"));
    service.delete(broken);
  }

  private record Answer(int version, String discount) {}

  private Answer evaluate(String model) {
    var outcome = service.evaluate(model, "discount", GOLD);
    return new Answer(outcome.model().version(), number(outcome.evaluation().results().get("discount")).toPlainString());
  }

  private static RegisterCommand command(String content, Boolean activate) {
    return new RegisterCommand(content, "test", null, VersionSource.API, activate, null);
  }

  private static void assertCode(Runnable call, Code code) {
    assertThatThrownBy(call::run).isInstanceOf(RuleEngineException.class)
        .extracting(ex -> ((RuleEngineException) ex).code()).isEqualTo(code);
  }

  private static BigDecimal number(Object value) {
    return new BigDecimal(String.valueOf(value));
  }
}
