package com.axonivy.utils.rule.engine.runtime;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * The runtime serves the Apache KIE DMN editor, in the version that fits its DMN engine.
 */
@QuarkusTest
class EditorPageTest {

  @Test
  void kieDmnEditorIsBundled() {
    byte[] editor = given().get("/editor/lib/dmn-editor.js").then()
        .statusCode(200)
        .contentType(containsString("javascript"))
        .extract().asByteArray();
    assertThat(editor.length).as("size of the standalone DMN editor").isGreaterThan(5_000_000);
    assertThat(new String(editor, 0, 2_000)).contains("DMNEditor");

    given().get("/editor/lib/VERSION.txt").then().statusCode(200)
        .body(containsString("@kie-tools/dmn-editor-standalone"));
    given().get("/editor/lib/LICENSE.txt").then().statusCode(200)
        .body(containsString("Apache License"));
  }

  @Test
  void editorScriptIsCompressed() {
    given().header("Accept-Encoding", "gzip").get("/editor/lib/dmn-editor.js").then()
        .statusCode(200)
        .header("Content-Encoding", "gzip");
  }
}
