package com.axonivy.utils.rule.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The records of the API are the contract between the runtime and the connector: they must turn into the JSON of
 * openapi.yaml and back.
 */
class ApiRecordsTest {

  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void evaluateRequest_sendsDecisionAndInputs_alsoNull() throws Exception {
    var input = new LinkedHashMap<String, Object>();
    input.put("customerTier", "Gold");
    input.put("orderAmount", 12000);
    input.put("region", null);
    var request = EvaluateRequest.builder().decision("discount").input(input).build();

    var text = json.writeValueAsString(request);

    assertThat(text).isEqualTo("{\"decision\":\"discount\",\"input\":{\"customerTier\":\"Gold\",\"orderAmount\":12000,\"region\":null}}");
    assertThat(json.readValue(text, EvaluateRequest.class)).isEqualTo(request);
  }

  @Test
  void evaluateRequest_withoutDecision_evaluatesTheWholeModel() throws Exception {
    var text = json.writeValueAsString(EvaluateRequest.builder().input(Map.of("a", 1)).build());
    assertThat(text).doesNotContain("decision");
  }

  @Test
  void evaluateResponse_isReadAsTheRuntimeWritesIt() throws Exception {
    var response = json.readValue("{\"model\":\"invoice-discount\",\"version\":3,\"decision\":\"discount\","
        + "\"result\":{\"discountPercent\":8},\"results\":{\"discount\":{\"discountPercent\":8}}}", EvaluateResponse.class);

    assertThat(response.version()).isEqualTo(3);
    assertThat(response.results()).containsOnlyKeys("discount");
    assertThat(response.messages()).isEmpty();
  }

  @Test
  void evaluateResponse_neverAnswersNullCollections() {
    var response = new EvaluateResponse("m", 1, null, null, null, null);
    assertThat(response.results()).isEmpty();
    assertThat(response.messages()).isEmpty();
  }

  @Test
  void registerVersionRequest_leavesOutWhatIsNotSet() throws Exception {
    var request = RegisterVersionRequest.builder().content("<definitions/>").source(VersionSource.PROJECT).build();
    assertThat(json.writeValueAsString(request)).isEqualTo("{\"content\":\"<definitions/>\",\"source\":\"PROJECT\"}");
  }

  @Test
  void modelVersion_keepsDatesWithTheirOffset() throws Exception {
    var created = OffsetDateTime.of(2026, 11, 20, 9, 30, 0, 0, ZoneOffset.ofHours(1));
    var version = new ModelVersion("invoice-discount", 2, VersionStatus.ACTIVE, VersionSource.PORTAL, "rule.owner",
        "Silver gets 3%", created, created, created, "rule.owner", 1, "invoice-discount", "ns", "abc", null);

    var text = json.writeValueAsString(version);

    assertThat(text).contains("\"createdAt\":\"2026-11-20T09:30:00+01:00\"").doesNotContain("created\":");
    var back = json.readValue(text, ModelVersion.class);
    assertThat(back).usingRecursiveComparison().ignoringFieldsOfTypes(OffsetDateTime.class).isEqualTo(version);
    assertThat(back.createdAt().isEqual(created)).as("the same instant").isTrue();
    assertThat(version.isActive()).isTrue();
  }

  @Test
  void errorResponse_hasACodeAMessageAndOptionalDetails() throws Exception {
    assertThat(json.writeValueAsString(new ErrorResponse("MODEL_NOT_FOUND", "Model 'x' not found.", List.of())))
        .isEqualTo("{\"code\":\"MODEL_NOT_FOUND\",\"message\":\"Model 'x' not found.\"}");
    var withDetails = new ErrorResponse("INVALID_MODEL", "Invalid", List.of(Message.error("Line 3: unknown element")));
    assertThat(json.readValue(json.writeValueAsString(withDetails), ErrorResponse.class)).isEqualTo(withDetails);
  }

  @Test
  void statusesAndSources_areTheNamesOfTheContract() {
    assertThat(VersionStatus.values()).extracting(Enum::name).containsExactly("DRAFT", "APPROVED", "ACTIVE", "RETIRED");
    assertThat(VersionSource.values()).extracting(Enum::name).containsExactly("PROJECT", "PORTAL", "API");
  }
}
