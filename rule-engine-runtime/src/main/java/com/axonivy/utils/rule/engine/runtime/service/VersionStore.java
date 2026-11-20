package com.axonivy.utils.rule.engine.runtime.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.api.VersionStatus;
import com.axonivy.utils.rule.engine.runtime.store.ModelVersionEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Transactional access to the stored versions. A content is stored only once per model.
 */
@ApplicationScoped
public class VersionStore {

  public record NewVersion(
      String model,
      String content,
      String contentHash,
      String dmnName,
      String dmnNamespace,
      String author,
      String description,
      VersionSource source,
      Boolean activate,
      OffsetDateTime effectiveDate) {}

  public record Registration(ModelVersionEntity version, boolean created) {}

  /**
   * Stores the content as a new draft of the model, unless the model has a version with the same content already.
   */
  @Transactional
  public Registration register(NewVersion request) {
    var existing = ModelVersionEntity.byHash(request.model(), request.contentHash());
    if (existing.isPresent()) {
      return new Registration(existing.get(), false);
    }
    var version = new ModelVersionEntity();
    version.model = request.model();
    version.version = ModelVersionEntity.nextVersion(request.model());
    version.content = request.content();
    version.contentHash = request.contentHash();
    version.dmnName = request.dmnName();
    version.dmnNamespace = request.dmnNamespace();
    version.author = request.author();
    version.description = request.description();
    version.source = request.source();
    version.status = VersionStatus.DRAFT;
    version.createdAt = OffsetDateTime.now();
    version.persist();
    return new Registration(version, true);
  }

  @Transactional
  public long delete(String model) {
    requireModel(model);
    return ModelVersionEntity.delete("model", model);
  }

  @Transactional
  public Optional<ModelVersionEntity> find(String model, int version) {
    return ModelVersionEntity.find(model, version);
  }

  @Transactional
  public ModelVersionEntity get(String model, int version) {
    return require(model, version);
  }

  @Transactional
  public List<ModelVersionEntity> versions(String model) {
    requireModel(model);
    return ModelVersionEntity.versionsOf(model);
  }

  @Transactional
  public List<String> modelNames() {
    return ModelVersionEntity.modelNames();
  }

  @Transactional
  public boolean exists(String model) {
    return ModelVersionEntity.count("model", model) > 0;
  }

  private static ModelVersionEntity require(String model, int version) {
    requireModel(model);
    return ModelVersionEntity.find(model, version)
        .orElseThrow(() -> RuleEngineException.versionNotFound(model, version));
  }

  private static void requireModel(String model) {
    if (ModelVersionEntity.count("model", model) == 0) {
      throw RuleEngineException.modelNotFound(model);
    }
  }
}
