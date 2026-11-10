# qits-network-access — working notes

Read `README.md` first: it is the design. This file is the conventions on top of it. Grow it with
the code.

## The rule that shapes everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere. `./mvnw verify` is the gate. The store is postgres and costs no docker:
`service/src/test/.../testdb/EmbeddedPg` spawns zonky's real binaries as a child process. **Never
Testcontainers, never a Quarkus dev service.**

The one address that is an exception is the platform Maven repository (`<repositories>` in the root
pom), where the qits-* jars live. Their versions are pinned in the root pom, exactly, and moved by
qits-maintenance's bumps — never by hand.

## Layout

- `domain/` — entities (`entity`), the allocation, validation, config render and the registry
  (`control`), and the published events (`events`). Owns the `networkaccess` datasource, persistence
  unit and the Flyway lineage at `db/networkaccess/migration`. Pure unit tests only.
- `service/` — the deployable: the REST surface (`api`), and every database-backed test.

## Conventions

- **Roles** are spelled out in each `@RolesAllowed` (see `api/Roles`): the READ set at class level,
  the WRITE set on each write method, which REPLACES the class-level one. Where `qits:admin-agent` is
  admitted, so is `qits:desk-admin` (`DeskAdminParityTest`). `RoleMatrixTest` pins the matrix.
- **The caller** is the token's `sub` (`api/Callers`), never the principal name.
- **Events** go through `QitsEventBus.publish` inside the write transaction, after the flush. A
  record component must never be called `name`: it would override `QitsEvent.name()`, the event's
  name on the wire, and be dropped from the payload. Use `peerName` with `@JsonProperty("name")`
  (`PeerEventPayloadTest`).
- **Every served type** is a nested record of `api/NetworkAccessWire` and is listed in
  `api/ApiWireReflection` (`ApiWireReflectionTest`).
- **Log writes at INFO** with the caller's subject and the peer's id. Never log a key.

## Generated files, and how to regenerate each

Every one of these compares by default and fails with a diff; `-Dgolden.update=true` rewrites.

| File | Written by |
|---|---|
| `docs/openapi.yml` | `OpenApiSchemaExportTest` (always rewrites; the diff is the review) |
| `docs/entities.json` | `EntityExposureTest` |
| `golden-masters/**` | `contracts/GoldenMasterRecordingTest` |
| `pacts/*_qits-idp-service.json` | `contracts/consumer/IdpPactTest` |
| `pacts/*_qits-events-service.json` | `EventstreamPactTest` |
| `docs/database/networkaccess.md` | `qits database diagram` (below) |

The entity diagram:

    ./mvnw -q -Dmaven.test.skip=true test-compile dependency:build-classpath \
      -Dmdep.outputFile=target/qits-classpath.txt -DincludeScope=runtime
    qits database diagram

A new door needs a golden master in the same commit: the release gate fails any operation of
`docs/openapi.yml` that `golden-masters/index.json` does not cover. A new provider state needs its
`@State` method on `ConsumerPactVerificationTest` (`ProviderStateCoverageTest`).

## The userflow catalogue

`stories/**IT` boot the PACKAGED service (`-DskipITs=false`) against `idp.MockIdp` with the OIDC
tenant on, and write their reports under `service/target/userstories/`. The java-service release
recipe requires that directory and publishes it as `@userflows/qits-network-access`.
