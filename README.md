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
| [rule-engine](rule-engine) | Ivy connector: program element `BusinessRuleTask`, client of the runtime (`com.axonivy.utils.rule.engine.api`: requests, responses, statuses), mapping, DMN generator. |
| [rule-engine/runtime](rule-engine/runtime) | Quarkus service, not part of the Ivy project around it: loads DMN models at runtime, stores their versions, evaluates decisions. Contract: [openapi.yaml](rule-engine/runtime/openapi.yaml). |
| [rule-engine-demo](rule-engine-demo) | The demo. |
| [rule-engine-test](rule-engine-test) | Tests of the connector. |

## Build and test

Java 25 and Maven. The Ivy modules are built with the project-build-plugin, which runs the tests in an Ivy engine.

| Command | What runs |
| --- | --- |
| `mvn verify` | Everything without Docker. |
| `mvn quarkus:dev -f rule-engine/runtime` | Runtime in dev mode on port 8090. |

## Decisions taken in this implementation

- **One project, two deployables.** `rule-engine` is the Ivy connector; its folder `runtime` is the decision runtime,
  a Maven module of its own (Java 21, Quarkus) that the iar leaves out. The two share no code, only JSON: each has
  its records for the requests and responses, and `ContractTest` fails if they do not have the same fields. So the
  image is built from `rule-engine/runtime` alone, without Ivy tooling. Only the tests of the runtime compile code of
  the connector, its packages `api` and `dmn.generator`. These two are plain Java 21 on purpose: an import such as
  `ch.ivyteam.ivy.environment.Ivy` in them breaks the tests of the runtime.
