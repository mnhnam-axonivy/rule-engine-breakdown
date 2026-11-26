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
