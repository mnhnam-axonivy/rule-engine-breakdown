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
 │   client, mapping             │   JSON       │   volume /data)                      │
 └───────────────┬───────────────┘              └───────────────────┬──────────────────┘
                 │            rule-engine-core                      │
                 └──────────  API records, DMN generator  ──────────┘
```

## Modules

| Module | Content |
| --- | --- |
| [rule-engine-core](rule-engine-core) | Ivy project, plain Java, shared by the connector and the runtime: the API of the runtime (`com.axonivy.utils.rule.engine.api`: requests, responses, statuses) and the DMN generator. |
| [rule-engine-runtime](rule-engine-runtime) | Quarkus service: loads DMN models at runtime, stores their versions, evaluates decisions. Compiles the sources of `rule-engine-core` into its own build. Contract: [openapi.yaml](rule-engine-runtime/openapi.yaml). |
| [rule-engine](rule-engine) | Ivy connector: program element `BusinessRuleTask`, client of the runtime, mapping. Requires `rule-engine-core`. |
| [rule-engine-demo](rule-engine-demo) | The demo. |
| [rule-engine-test](rule-engine-test) | Tests of the connector. |

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

## Build and test

Java 25 and Maven. The Ivy modules are built with the project-build-plugin, which runs the tests in an Ivy engine.

| Command | What runs |
| --- | --- |
| `mvn verify` | Everything without Docker. |
| `docker compose build` | The image `axonivy/rule-engine-runtime:dev`, built from the root of the repository. |
| `mvn quarkus:dev -f rule-engine-runtime` | Runtime in dev mode on port 8090. |

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

## Decisions taken in this implementation

- **One shared core, two deployables.** `rule-engine-core` is an Axon Ivy project, so Ivy projects require it like
  any other. It is plain Java on purpose: the runtime adds its `src` folder as a source folder and compiles it
  with Java 21, so the Docker build needs no Ivy tooling, and any use of the Ivy API in the core breaks the runtime
  build at once. The OpenAPI annotations on its records document the runtime's contract; in Ivy they are a
  compile-only dependency and are ignored at runtime.

- **Versions are stored in the runtime container**, not in the system database of the Ivy Engine as MARP-4895
  describes. The container holds the engine and an embedded H2 database on the volume `/data`.
  Consequence: the runtime is not stateless, several runtime instances need a shared database
  (the store is JPA, switching the database is a configuration and driver change).
