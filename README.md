# Axon Ivy Rules Engine

Replacement of the built-in Drools engine of Axon Ivy: DMN rules run in a separate decision runtime next to
the Ivy Engine and are used in processes through the program element **Business Rule Task**.
Based on Kogito and Drools from the open source project Apache KIE.

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
| [rule-engine-demo](rule-engine-demo) | The demo. |
| [rule-engine-test](rule-engine-test) | Tests of the connector, with `MockRuntime`: a stand-in for the runtime that tests program. |

## Run it

```
docker compose up -d --build      # decision runtime on http://localhost:8090
```

The runtime is ready when `http://localhost:8090/q/health/ready` is UP. The API is described at
`http://localhost:8090/q/swagger-ui`. Load a model and evaluate it:

```
curl -X POST -H "Content-Type: application/xml" --data-binary @discount.dmn      http://localhost:8090/api/models/discount/versions
curl -X POST -H "Content-Type: application/json"      -d '{"decision":"discount","input":{"customerTier":"Gold","orderAmount":12000}}'      http://localhost:8090/api/models/discount/evaluate
```

Every input of the decisions it needs must be in `input`, `null` if it has no value. Set `RULE_ENGINE_API_KEY` to
protect the API; the versions live in the volume `rule-engine-data`.

Change the rules in the browser at `http://localhost:8090/editor/`: open a model, edit its decision table, save it as
a new version, as draft or live. The browser check of that page is in [internal/editor-smoke](internal/README.md).

## Build and test

Java 25 and Maven. The Ivy modules are built with the project-build-plugin, which runs the tests in an Ivy engine.

| Command | What runs |
| --- | --- |
| `mvn verify` | Everything without Docker. |
| `docker compose build` | The image `axonivy/rule-engine-runtime:dev`, built from `rule-engine/runtime`. |
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
