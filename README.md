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

## Build and test

Java 25 and Maven. The Ivy modules are built with the project-build-plugin, which runs the tests in an Ivy engine.

| Command | What runs |
| --- | --- |
| `mvn verify` | Everything without Docker. |
| `mvn quarkus:dev -f rule-engine-runtime` | Runtime in dev mode on port 8090. |

## Decisions taken in this implementation

- **One shared core, two deployables.** `rule-engine-core` is an Axon Ivy project, so Ivy projects require it like
  any other. It is plain Java on purpose: the runtime adds its `src` folder as a source folder and compiles it
  with Java 21, so the Docker build needs no Ivy tooling, and any use of the Ivy API in the core breaks the runtime
  build at once. The OpenAPI annotations on its records document the runtime's contract; in Ivy they are a
  compile-only dependency and are ignored at runtime.
