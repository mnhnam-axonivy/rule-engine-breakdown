package com.axonivy.utils.rule.engine.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.api.VersionStatus;
import com.axonivy.utils.rule.engine.runtime.TestModels;
import com.axonivy.utils.rule.engine.runtime.service.RuleEngineException.Code;
import com.axonivy.utils.rule.engine.runtime.service.VersionStore.NewVersion;
import com.axonivy.utils.rule.engine.runtime.store.ModelVersionEntity;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Versions are stored in the embedded database: numbered per model, and a content only once.
 */
@QuarkusTest
class VersionStoreTest {

  @Inject
  VersionStore store;

  @Test
  void versionsAreNumberedPerModel() {
    var model = TestModels.uniqueName("numbers");
    var other = TestModels.uniqueName("numbers");

    assertThat(store.register(version(model, TestModels.discount(10))).version().version).isEqualTo(1);
    assertThat(store.register(version(model, TestModels.discount(15))).version().version).isEqualTo(2);
    assertThat(store.register(version(other, TestModels.discount(10))).version().version).isEqualTo(1);

    assertThat(store.versions(model)).extracting(entity -> entity.version).as("newest first").containsExactly(2, 1);
    assertThat(store.modelNames()).contains(model, other);
  }

  @Test
  void sameContent_isStoredOnce() {
    var model = TestModels.uniqueName("once");
    var first = store.register(version(model, TestModels.discount(10)));
    var again = store.register(version(model, TestModels.discount(10)));

    assertThat(first.created()).isTrue();
    assertThat(again.created()).isFalse();
    assertThat(again.version().version).isEqualTo(first.version().version);
    assertThat(store.versions(model)).hasSize(1);
  }

  @Test
  void aNewVersion_isADraft_withItsOriginAndAuthor() {
    var model = TestModels.uniqueName("draft");
    var stored = store.register(new NewVersion(model, TestModels.discount(10), "hash-1", "discount", "ns",
        "rule.owner", "Gold gets 10%", VersionSource.PORTAL, null, null)).version();

    var read = store.get(model, stored.version);
    assertThat(read.status).isEqualTo(VersionStatus.DRAFT);
    assertThat(read.source).isEqualTo(VersionSource.PORTAL);
    assertThat(read.author).isEqualTo("rule.owner");
    assertThat(read.content).isEqualTo(TestModels.discount(10));
    assertThat(read.createdAt).isNotNull();
  }

  @Test
  void unknownModelOrVersion_isAnswered_withItsCode() {
    var model = TestModels.uniqueName("unknown");
    assertThatThrownBy(() -> store.versions(model)).isInstanceOf(RuleEngineException.class)
        .extracting(ex -> ((RuleEngineException) ex).code()).isEqualTo(Code.MODEL_NOT_FOUND);

    store.register(version(model, TestModels.discount(10)));
    assertThatThrownBy(() -> store.get(model, 7)).isInstanceOf(RuleEngineException.class)
        .extracting(ex -> ((RuleEngineException) ex).code()).isEqualTo(Code.VERSION_NOT_FOUND);
  }

  @Test
  void deleteRemovesEveryVersionOfTheModel() {
    var model = TestModels.uniqueName("delete");
    store.register(version(model, TestModels.discount(10)));
    store.register(version(model, TestModels.discount(15)));

    assertThat(store.delete(model)).isEqualTo(2);
    assertThat(store.exists(model)).isFalse();
    assertThat(ModelVersionEntity.count()).isGreaterThanOrEqualTo(0);
  }

  private static NewVersion version(String model, String content) {
    return new NewVersion(model, content, Integer.toHexString(content.hashCode()), "discount", "ns", "test", null,
        VersionSource.API, null, null);
  }
}
