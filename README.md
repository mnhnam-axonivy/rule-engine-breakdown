# Axon Ivy Rules Engine

Replacement of the built-in Drools engine of Axon Ivy: DMN rules run in a separate decision runtime next to
the Ivy Engine and are used in processes through the program element **Business Rule Task**.
Based on Kogito and Drools from the open source project Apache KIE.

User documentation: [rule-engine-product/README.md](rule-engine-product/README.md).

This repository is built pull request by pull request, following [BREAKDOWN.md](BREAKDOWN.md).

```
 Axon Ivy                                        Decision runtime (one container)
 ┌───────────────────────────────┐              ┌──────────────────────────────────────┐
 │ Ivy project                   │              │ REST API  /api/models/...            │
 │   dmn/*.dmn                   │  register    │   load version, activate, rollback   │
 │   process with                │ ───────────► │   evaluate decision                  │
 │   Business Rule Task          │  evaluate    │ DMN engine (Apache KIE, interpreted) │
 │ rule-engine connector         │ ◄─────────── │ Database with all versions (H2,      │
 │   client, mapping, generator  │   JSON       │   volume /data)                      │
 └───────────────────────────────┘              └──────────────────────────────────────┘
```

## Modules

| Module | Content |
| --- | --- |
| [rule-engine](rule-engine) | Ivy connector: program element `BusinessRuleTask`, client of the runtime (`com.axonivy.utils.rule.engine.api`: requests, responses, statuses), mapping, DMN generator, Java API `RuleEngine`. Variables `RuleEngine.*` in `config/variables.yaml`. |
| [rule-engine/runtime](rule-engine/runtime) | Quarkus service, not part of the Ivy project around it: loads DMN models at runtime, stores their versions, evaluates decisions. Serves the DMN editor page `/editor/` (Apache KIE DMN editor, fetched from npm by the build). Contract: [openapi.yaml](rule-engine/runtime/openapi.yaml). |
| [rule-engine-demo](rule-engine-demo) | The demo: an invoice with five lines whose discount is decided by one decision table, `dmn/invoice-discount.dmn`, from the customer tier and the order amount. |
| [rule-engine-test](rule-engine-test) | Tests of the connector, with `MockRuntime`: a stand-in for the runtime that tests program. |
| [rule-engine-product](rule-engine-product) | Market product: README, product.json. |
| [portal](portal), [portal-components](portal-components) | Axon Ivy Portal 14.0.1.2, imported unchanged from [axonivy-market/portal](https://github.com/axonivy-market/portal), tag `14.0.1.2` (commit `474b8205`), as the base of the tab **Rule Management**. Not part of the Maven build of the rule engine, see [Rule Management in the Portal](#rule-management-in-the-portal). |

## Run it

```
docker compose up -d --build      # decision runtime on http://localhost:8090
```

The runtime is ready when `http://localhost:8090/q/health/ready` is UP. The API is described at
`http://localhost:8090/q/swagger-ui`. Load the model of the demo and evaluate it:

```
curl -X POST -H "Content-Type: application/xml" --data-binary @rule-engine-demo/dmn/invoice-discount.dmn \
     http://localhost:8090/api/models/invoice-discount/versions
curl -X POST -H "Content-Type: application/json" \
     -d '{"decision":"discount","input":{"customerTier":"Gold","orderAmount":12000}}' \
     http://localhost:8090/api/models/invoice-discount/evaluate
# {"decision":"discount","model":"invoice-discount","result":{"discountPercent":8,
#  "discountReason":"Gold, order of 10,000 or more"},"results":{...},"version":1}
```

Import `rule-engine`, `rule-engine-demo` into the Designer and start `InvoicePricingDemo/start.ivp`:
an invoice with five lines and its discount, decided by the rules. Change the customer tier or a quantity and click
**Apply rules**. The variable `RuleEngine.Url` points to `http://localhost:8090` by default.

Every input of the decisions it needs must be in `input`, `null` if it has no value. Set `RULE_ENGINE_API_KEY` to
protect the API; the versions live in the volume `rule-engine-data`.

Change the rules in the browser at `http://localhost:8090/editor/`: open a model, edit its decision table, save it as
a new version, as draft or live. The browser check of that page is in [internal/editor-smoke](internal/README.md).

## Build and test

Java 25 and Maven. The Ivy modules are built with the project-build-plugin, which runs the tests in an Ivy engine.

| Command | What runs |
| --- | --- |
| `mvn verify` | Everything without Docker: tests of the runtime (REST API, version rules, DMN engine) and tests of the connector with a mocked runtime. |
| `docker compose build` | The image `axonivy/rule-engine-runtime:dev`, built from `rule-engine/runtime`. |
| `docker compose build` then `mvn verify -Pcontainer.test -pl rule-engine-test -am` | `RuleEngineContainerTest`: the connector against the real runtime in a container (Testcontainers). `-Drule.engine.image=…` tests another image. If Testcontainers times out waiting for the container (seen with Rancher Desktop), set `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1`. |
| `mvn test -Pperformance -f rule-engine/runtime` | Performance baseline, written to `rule-engine/runtime/target/performance-baseline.md`. |
| `mvn quarkus:dev -f rule-engine/runtime` | Runtime in dev mode on port 8090. |

## How the REST client is built

The client of the connector follows the design of LangChain4j:

- `client.http`: the transport abstraction `HttpClient`, `HttpClientBuilder`, `HttpRequest`,
  `SuccessfulHttpResponse`, `HttpException` and `LoggingHttpClient`. The implementation is found through the
  service provider interface `HttpClientBuilderFactory` (`HttpClientBuilderLoader`).
- `client.http.ivy`: the default transport. It uses the REST client `ruleEngine` of Axon Ivy (Jersey), the same
  way Smart Workflow plugs the Ivy REST client into LangChain4j. Calls appear in the tracing of the engine.
- `client`: `RuleEngineClient` with a builder (`baseUrl`, `apiKey`, timeouts, `logRequests`, custom headers),
  and exceptions for unreachable, timeout and error answers.
- The typed requests and responses are the records of `com.axonivy.utils.rule.engine.api`. The runtime has records
  of its own; `ContractTest` fails if the two do not have the same fields.

## Spike result: runtime loading (MARP-4893)

Runtime loading works and is used. The fallback (Kogito code generation with a pipeline template) is not needed.

- DMN files are compiled in memory with `DMNRuntimeBuilder` of `kie-dmn-core`, the same engine Kogito uses.
  No code generation, no KieContainer, no restart. This is the mechanism of the Kogito JIT executor.
- Kogito's Quarkus extension is not used: it compiles models at build time, which is the opposite of what we need.
- A version is an immutable compiled model. Activating another version swaps one reference,
  evaluations that are running keep the version they started with.
- Versions: Apache KIE 10.2.0 on Quarkus 3.38. It reads DMN 1.1 to 1.6. The generator writes DMN 1.5,
  which the KIE editor upgrades to 1.6 on save.
- Risk that stays: `org.kie.dmn.core.internal.utils` is internal API of KIE. It is isolated in
  `DmnCompiler` and `DmnEvaluator` and covered by tests, so an upgrade of KIE shows a break at once.
- Inputs are type-checked (`rule.engine.type-check=true`): a value outside of the allowed values of an enum
  fails the evaluation instead of silently matching no rule.

## Performance baseline (MARP-4893)

Decision table with 1,000 rules, 2 inputs (number range, string), hit policy FIRST, 206 KB of DMN.
Measured on a 12 core Windows 11 machine with Java 25, runtime and client on the same host.

| Step | Time |
| --- | --- |
| Validate (schema, model, compilation), first call | 3.2 s |
| Compile (load a version) | 0.7 s |

| Evaluation | mean | p50 | p95 | p99 |
| --- | --- | --- | --- | --- |
| In the engine, last rule matches | 0.26 ms | 0.28 ms | 0.43 ms | 0.56 ms |
| In the engine, no rule matches | 0.36 ms | 0.37 ms | 0.58 ms | 0.77 ms |
| REST API, 1 client | 1.59 ms | 1.54 ms | 2.34 ms | 2.75 ms |

Throughput through the REST API with 8 parallel clients: 2,243 evaluations per second.
The position of the matching rule does not matter: the engine evaluates all rows of a table.

## Rule Management in the Portal

The Portal has no dependency on the rule engine. It finds it the way it finds Smart Workflow: by the signature of
a callable sub process in the security context (`SubProcessCallStartEvent.find` with `SearchScope.SECURITY_CONTEXT`).
If it is there, the Admin Settings show the tab **Rule Management**; otherwise nothing changes. The signatures below
are the contract between the two: the Portal side is built against them.

| Side | Where | What |
| --- | --- | --- |
| Rule engine | `rule-engine/process/Portal/RuleManagement.p.json`, `com.axonivy.utils.rule.engine.portal.RuleManagement` | The bridge. One start per call, every start answers `result` and `error`, made of JDK types only (String, Integer, Boolean, List, Map), so the Portal needs no class of the rule engine. |
| Rule engine | `com.axonivy.utils.rule.engine.portal.DmnEditorScript` | REST resource `{app}/api/rule-engine/dmn-editor/dmn-editor.js`: the KIE DMN editor, fetched once from the runtime (`/editor/lib/dmn-editor.js`) and kept in memory. The browser never talks to the runtime. |
| Portal | `com.axonivy.portal.rule.RuleEngineBridge` | Detection (`isAvailable()`) and the calls, through `IvyAdapterService`. |
| Portal | `com.axonivy.portal.bean.RuleManagementBean`, component `com.axonivy.portal.component.RuleManagement`, `rule-management.js` | The tab: the rules, their versions, the editor. Texts under `/ch.ivy.addon.portalkit.ui.jsf/components/RuleManagement` in all five languages. |

The Portal of this repository is the release 14.0.1.2, imported unchanged in one commit: every change made for the
rule engine is a diff against that commit. Its files keep the line endings of the release (`.gitattributes`).
Build it with `mvn install -f portal-components` and `mvn verify -f portal`.

The contract, tested by `TestRuleManagement` in `rule-engine-test`:

| Signature | Result |
| --- | --- |
| `ruleManagementModels()` | The models of the runtime and the `.dmn` files of the projects the runtime does not know yet: name, onRuntime, activeVersion, latestVersion, versionCount, decisions, project, file. The Portal detects the rule engine by this start. |
| `ruleManagementInfo()` | runtimeUrl, reachable, editorScript (link of the editor script on this engine), modelPrefix |
| `ruleManagementVersions(String)` | The versions of a model, newest first: version, status, source, author, description, createdAt, activatedAt, activatedBy, effectiveDate, previousVersion |
| `ruleManagementContent(String,Integer)` | model, version, dmn; version null opens the active one, a project file is read from its project |
| `ruleManagementValidate(String)` | valid, messages (the findings of the DMN engine) |
| `ruleManagementSave(String,String,String,String,Boolean)` | model, dmn, author, description, activate: a new version with the source *portal*, as draft or live |
| `ruleManagementActivate(String,Integer,String)` | model, version, author |
| `ruleManagementRollback(String,String)` | model, author |

`error` is null on success, else a map with `code` (UNREACHABLE, TIMEOUT, NOT_CONFIGURED, an error code of the
runtime such as INVALID_MODEL, or FAILED), `message` and `details`.

## Decisions taken in this implementation

- **One project, two deployables.** `rule-engine` is the Ivy connector; its folder `runtime` is the decision runtime,
  a Maven module of its own (Java 21, Quarkus) that the iar leaves out. The two share no code, only JSON: each has
  its records for the requests and responses, and `ContractTest` fails if they do not have the same fields. So the
  image is built from `rule-engine/runtime` alone, without Ivy tooling. Only the tests of the runtime compile code of
  the connector, its packages `api` and `dmn.generator`. These two are plain Java 21 on purpose: an import such as
  `ch.ivyteam.ivy.environment.Ivy` in them breaks the tests of the runtime.

- **Versions are stored in the runtime container**, not in the system database of the Ivy Engine as MARP-4895
  describes. The container holds the engine and an embedded H2 database on the volume `/data`.
  Consequence: the runtime is not stateless, several runtime instances need a shared database
  (the store is JPA, switching the database is a configuration and driver change).

- **Model names**: the name of a model on the runtime is the file name of the `.dmn` file.
  `RuleEngine.ModelPrefix` separates applications that share one runtime.

- **Packed projects**: an engine keeps a deployed project as its `.iar`; `Project.directory()` is then that file.
  `ProjectDmnFiles` reads the `.dmn` files from it as a zip (under `META-INF/ivy`), so the Business Rule Task and
  the Portal find them on an engine as in the Designer. Writing a file (New DMN, `RuleEngine.export`)
  needs the project folder of the Designer.

- **Dropdown**: the inscription API of 14.0.1 has no single-select and no button. The decision dropdown is the
  multi-select widget, the first selected entry is used.

- **Field picker**: fields are entered as field paths in text fields, until Core delivers a data picker.

- **Mapping by name only**: inputs and results are always found by name. A value that has to be prepared, or a
  field with another name, is set in the process data before the element, for example in a Script. Input fields
  and Output fields only describe a new DMN.

- **Inputs of the version that answers**: the Business Rule Task takes the inputs a decision needs from the `.dmn` file
  of the project. A version activated in the Portal or through the API answers instead of the file, and may need
  more, e.g. a column a rule owner added. So the task also sends the inputs of the active version when it did not
  come from a project, found in the process data by name. What the runtime has active is asked at most every five
  seconds; if another version answers than expected, the task asks again and evaluates once more.

- **New DMN...**: without a change event, selecting the entry cannot generate the file at that moment.
  The file is generated when an inscription mask of the project is opened the next time, or when the element
  is executed. The selection switches to the generated decision through the config migrator of the element.

- **Portal editor in the engine, not the runtime**: the Portal tab could embed the page `/editor/` of the
  runtime in a frame. It does not: that page needs the runtime to be reachable from every browser and the API key
  in the browser, and it has its own look and its own list of models. The tab is built from Portal components;
  only the KIE editor itself is embedded, served by the engine.
