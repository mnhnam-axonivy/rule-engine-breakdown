package com.axonivy.utils.rule.engine.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.runtime.api.model.VersionSource;
import com.axonivy.utils.rule.engine.runtime.api.model.VersionStatus;
import com.axonivy.utils.rule.engine.runtime.TestModels;
import com.axonivy.utils.rule.engine.runtime.service.RuleEngineException.Code;
import com.axonivy.utils.rule.engine.runtime.service.VersionStore.NewVersion;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Versions are stored in the embedded database and follow their life cycle: exactly one active version per model,
 * and a project deployment never replaces a version that a person activated.
 */
@QuarkusTest
class VersionStoreTest {

  @Inject
  VersionStore store;

  @Test
  void versionsAreNumberedPerModel() {
    var model = TestModels.uniqueName("numbers");
    var other = TestModels.uniqueName("numbers");

    assertThat(store.register(version(model, 10, VersionSource.API, null)).version().version).isEqualTo(1);
    assertThat(store.register(version(model, 15, VersionSource.API, null)).version().version).isEqualTo(2);
    assertThat(store.register(version(other, 10, VersionSource.API, null)).version().version).isEqualTo(1);

    assertThat(store.versions(model)).extracting(entity -> entity.version).as("newest first").containsExactly(2, 1);
    assertThat(store.modelNames()).contains(model, other);
  }

  @Test
  void sameContent_isStoredOnce() {
    var model = TestModels.uniqueName("once");
    var first = store.register(version(model, 10, VersionSource.API, null));
    var again = store.register(version(model, 10, VersionSource.API, null));

    assertThat(first.created()).isTrue();
    assertThat(again.created()).isFalse();
    assertThat(again.version().version).isEqualTo(first.version().version);
    assertThat(store.versions(model)).hasSize(1);
  }

  @Test
  void firstVersion_goesLive() {
    var model = TestModels.uniqueName("first");
    var first = store.register(version(model, 10, VersionSource.PROJECT, null)).version();

    assertThat(first.status).isEqualTo(VersionStatus.ACTIVE);
    assertThat(first.activatedAt).isNotNull();
    assertThat(first.previousVersion).isNull();
  }

  @Test
  void versionBesideAnActiveOne_isADraft_withItsOriginAndAuthor() {
    var model = TestModels.uniqueName("draft");
    store.register(version(model, 10, VersionSource.API, null));
    var stored = store.register(new NewVersion(model, TestModels.discount(15), "hash-15", "discount", "ns",
        "rule.owner", "Gold gets 15%", VersionSource.PORTAL, null, null)).version();

    var read = store.get(model, stored.version);
    assertThat(read.status).isEqualTo(VersionStatus.DRAFT);
    assertThat(read.source).isEqualTo(VersionSource.PORTAL);
    assertThat(read.author).isEqualTo("rule.owner");
    assertThat(read.createdAt).isNotNull();
  }

  @Test
  void explicitChoiceOfTheCaller_wins() {
    var model = TestModels.uniqueName("explicit");
    assertThat(store.register(version(model, 10, VersionSource.API, false)).version().status)
        .as("even the first version stays a draft").isEqualTo(VersionStatus.DRAFT);
    store.activate(model, 1, "admin", null);

    var live = store.register(version(model, 15, VersionSource.API, true)).version();

    assertThat(live.status).isEqualTo(VersionStatus.ACTIVE);
    assertThat(live.previousVersion).isEqualTo(1);
    assertThat(store.get(model, 1).status).isEqualTo(VersionStatus.RETIRED);
  }

  @Test
  void projectDeployment_replacesAProjectVersion() {
    var model = TestModels.uniqueName("project");
    store.register(version(model, 10, VersionSource.PROJECT, null));
    var redeployed = store.register(version(model, 15, VersionSource.PROJECT, null)).version();

    assertThat(redeployed.status).isEqualTo(VersionStatus.ACTIVE);
    assertThat(store.get(model, 1).status).isEqualTo(VersionStatus.RETIRED);
  }

  @Test
  void projectDeployment_doesNotOverwriteAVersionActivatedFromThePortal() {
    var model = TestModels.uniqueName("portal");
    store.register(version(model, 10, VersionSource.PROJECT, null));
    var fromPortal = store.register(version(model, 12, VersionSource.PORTAL, true)).version();
    var redeployed = store.register(version(model, 15, VersionSource.PROJECT, null)).version();

    assertThat(redeployed.status).isEqualTo(VersionStatus.DRAFT);
    assertThat(store.get(model, fromPortal.version).status).isEqualTo(VersionStatus.ACTIVE);
    assertThat(store.activeRefs()).filteredOn(ref -> ref.model().equals(model)).hasSize(1);
  }

  @Test
  void versionWithAnEffectiveDate_waitsApproved_andGoesLiveWhenItIsDue() throws InterruptedException {
    var model = TestModels.uniqueName("effective");
    store.register(version(model, 10, VersionSource.API, null));
    store.register(version(model, 15, VersionSource.API, false));

    var scheduled = store.activate(model, 2, "approver", OffsetDateTime.now().plusSeconds(1));
    assertThat(scheduled.status).isEqualTo(VersionStatus.APPROVED);
    assertThat(store.activateDueVersions()).noneMatch(due -> due.model.equals(model));

    Thread.sleep(1500);
    assertThat(store.activateDueVersions()).anyMatch(due -> due.model.equals(model) && due.version == 2);
    assertThat(store.get(model, 2).status).isEqualTo(VersionStatus.ACTIVE);
    assertThat(store.get(model, 1).status).isEqualTo(VersionStatus.RETIRED);
  }

  @Test
  void onlyADraft_canBeApproved() {
    var model = TestModels.uniqueName("approve");
    store.register(version(model, 10, VersionSource.API, null));
    store.register(version(model, 15, VersionSource.API, false));

    assertThat(store.approve(model, 2).status).isEqualTo(VersionStatus.APPROVED);
    assertInvalidState(() -> store.approve(model, 1));
  }

  @Test
  void onlyAVersionThatIsNotActive_canBeRetired() {
    var model = TestModels.uniqueName("retire");
    store.register(version(model, 10, VersionSource.API, null));
    store.register(version(model, 15, VersionSource.API, false));

    assertInvalidState(() -> store.retire(model, 1));
    assertThat(store.retire(model, 2).status).isEqualTo(VersionStatus.RETIRED);
  }

  @Test
  void rollback_walksBackThroughTheVersionsThatWereActive() {
    var model = TestModels.uniqueName("rollback");
    store.register(version(model, 10, VersionSource.API, null));
    store.register(version(model, 15, VersionSource.API, true));
    store.register(version(model, 20, VersionSource.API, true));

    assertThat(store.rollback(model, "admin").version).isEqualTo(2);
    assertThat(store.rollback(model, "admin").version).isEqualTo(1);
    assertInvalidState(() -> store.rollback(model, "admin"));
    assertThat(store.activeRefs()).filteredOn(ref -> ref.model().equals(model))
        .singleElement().extracting(VersionStore.ActiveRef::version).isEqualTo(1);
  }

  @Test
  void unknownModelOrVersion_isAnswered_withItsCode() {
    var model = TestModels.uniqueName("unknown");
    assertThatThrownBy(() -> store.versions(model)).isInstanceOf(RuleEngineException.class)
        .extracting(ex -> ((RuleEngineException) ex).code()).isEqualTo(Code.MODEL_NOT_FOUND);

    store.register(version(model, 10, VersionSource.API, null));
    assertThatThrownBy(() -> store.get(model, 7)).isInstanceOf(RuleEngineException.class)
        .extracting(ex -> ((RuleEngineException) ex).code()).isEqualTo(Code.VERSION_NOT_FOUND);
  }

  @Test
  void deleteRemovesEveryVersionOfTheModel() {
    var model = TestModels.uniqueName("delete");
    store.register(version(model, 10, VersionSource.API, null));
    store.register(version(model, 15, VersionSource.API, null));

    assertThat(store.delete(model)).isEqualTo(2);
    assertThat(store.exists(model)).isFalse();
  }

  private static void assertInvalidState(Runnable change) {
    assertThatThrownBy(change::run).isInstanceOf(RuleEngineException.class)
        .extracting(ex -> ((RuleEngineException) ex).code()).isEqualTo(Code.INVALID_STATE);
  }

  private static NewVersion version(String model, int goldDiscount, VersionSource source, Boolean activate) {
    var content = TestModels.discount(goldDiscount);
    return new NewVersion(model, content, "hash-" + goldDiscount, "discount", "ns", "test", null, source, activate, null);
  }
}
