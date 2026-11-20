package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.dmn.DmnModelInfo;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.IvyTest;

/**
 * The .dmn files of a project, and what they offer: decisions, inputs, results.
 */
@IvyTest
class TestProjectDmn {


  @BeforeEach
  public void refresh() {
    ProjectDmnFiles.refresh();
  }

  @Test
  void dmnFilesOfTheProjectAndItsRequiredProjects_areFound() {
    var files = ProjectDmnFiles.list(Project.current());
    assertThat(files).extracting(ProjectDmnFiles.DmnFile::model).contains("discount", "invoice-approval");

    var discount = ProjectDmnFiles.load(ProjectDmnFiles.find(Project.current(), "discount").orElseThrow());
    assertThat(discount.parseError()).isNull();
    assertThat(discount.info().name()).isEqualTo("discount");
    assertThat(discount.info().inputNames()).containsExactly("purchaseAmount", "type", "country");
    assertThat(discount.hash()).hasSize(64).isEqualTo(ProjectDmnFiles.hash(discount.content().replace("\n", "\r\n")));
  }

  /** An engine keeps a deployed project packed: its directory is the .iar, a zip with the files under META-INF/ivy. */
  @Test
  void dmnFilesOfAPackedProject_areFound() throws IOException {
    Path iar = Files.createTempFile("packed-project", ".iar");
    try {
      String dmn = Files.readString(ProjectDmnFiles.find(Project.current(), "discount").orElseThrow().path());
      try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(iar))) {
        zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/ivy/dmn/discount.dmn"));
        zip.write(dmn.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/ivy/target/classes/dmn/copy.dmn"));
        zip.write(dmn.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }

      Path root = ProjectDmnFiles.root(iar);

      assertThat(root).isNotNull();
      assertThat(root.toString()).isEqualTo("/META-INF/ivy");
      try (var paths = Files.walk(root)) {
        assertThat(paths.map(Path::toString).filter(path -> path.endsWith(".dmn")))
            .contains("/META-INF/ivy/dmn/discount.dmn");
      }
      var file = new ProjectDmnFiles.DmnFile("discount", root.resolve("dmn/discount.dmn"), "packed", "packed:1");
      assertThat(ProjectDmnFiles.load(file).info().inputNames()).containsExactly("purchaseAmount", "type", "country");
      assertThat(ProjectDmnFiles.root(Files.createTempDirectory("unpacked"))).as("a project folder is its own root").isNotNull();
    } finally {
      try {
        Files.deleteIfExists(iar);
      } catch (IOException ex) {
        iar.toFile().deleteOnExit(); // still open: ProjectDmnFiles keeps packed projects open
      }
    }
  }

  @Test
  void decisionKnowsItsInputs_alsoThroughOtherDecisions() {
    var file = ProjectDmnFiles.find(Project.current(), "invoice-approval").orElseThrow();
    DmnModelInfo info = ProjectDmnFiles.load(file).info();

    assertThat(info.decisions()).extracting(DmnModelInfo.Decision::name)
        .containsExactly("riskLevel", "approval", "dueDate");
    assertThat(info.decision("riskLevel").orElseThrow().requiredInputs())
        .containsExactlyInAnyOrder("amount", "supplierRating", "hasPurchaseOrder");
    assertThat(info.decision("approval").orElseThrow().requiredInputs())
        .as("approval needs riskLevel, which needs its own inputs")
        .containsExactlyInAnyOrder("amount", "supplierRating", "hasPurchaseOrder");
    assertThat(info.decision("dueDate").orElseThrow().requiredInputs()).containsExactly("invoiceDate");
    assertThat(info.decision("dueDate").orElseThrow().typeRef()).isEqualTo("date");
  }

  @Test
  void decisionKnowsItsResults_andTheTypesForAReader() {
    var file = ProjectDmnFiles.find(Project.current(), "invoice-approval").orElseThrow();
    DmnModelInfo info = ProjectDmnFiles.load(file).info();

    assertThat(info.inputTypes()).as("an item definition is resolved to what it stands for")
        .containsEntry("amount", "number")
        .containsEntry("supplierRating", "string")
        .containsEntry("hasPurchaseOrder", "boolean")
        .containsEntry("invoiceDate", "date");
    var riskLevel = info.decision("riskLevel").orElseThrow();
    assertThat(riskLevel.outputs()).containsExactly(new DmnModelInfo.Output("riskLevel", "string"));
    assertThat(info.describeInputs(riskLevel)).isEqualTo("amount (number), supplierRating (string), hasPurchaseOrder (boolean)");
    assertThat(DmnModelInfo.describeOutputs(info.decision("approval").orElseThrow()))
        .as("a table with several output columns gives the entries of a structure")
        .isEqualTo("nextStep (string), fourEyes (boolean)");
    assertThat(DmnModelInfo.describeOutputs(info.decision("dueDate").orElseThrow())).isEqualTo("dueDate (date)");
  }

  @Test
  void editorUrl() {
    assertThat(RuleEngineConfig.editorUrl("http://localhost:8090")).isEqualTo("http://localhost:8090/editor/");
    assertThat(RuleEngineConfig.editorUrl(" https://rules.example.com/runtime/ ")).isEqualTo("https://rules.example.com/runtime/editor/");
    assertThat(RuleEngineConfig.editorUrl("")).isEqualTo("<RuleEngine.Url>/editor/");
  }
}
