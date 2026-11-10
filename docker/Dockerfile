# qits-network-access as a GraalVM-native container IMAGE, the shape of qits-containers': a Mandrel
# builder stage compiles the binary, an `sbom` stage holds its CycloneDX document, and a minimal
# runtime stage carries only the binary.
#
# Build from the repo root:
#
#     docker build -t qits/qits-network-access:latest -f docker/Dockerfile .
#
# THE CREDENTIAL IS REQUIRED: the reactor depends on qits-* jars published to the platform's own
# Maven repository and nowhere else, and both hosts answer 401 anonymously. The release recipe passes
# the two secrets below.
#
# THIS IMAGE DOES NOT RUN WITH AN EMPTY ENVIRONMENT, by design: both datasources are the platform's
# generic resource contract (QITS_RESOURCE_DB_* and QITS_RESOURCE_EVENTSTREAM_*), which
# qits-deployments injects from .config/qits/deployments.yml. A fallback store would be a peer list
# nobody meant.
#
# STAGES STILL TO COME (the design page, sections 5 and 6), and where they go:
#   - `access-client`, a Go stage FROM mirror.qits.${QITS_DOMAIN}/hub/library/golang:1.25 building
#     access-client/cmd/qits-node-tunnel and cmd/qits-access-companion; the runtime stage then copies
#     qits-node-tunnel to /work/node-tunnel/qits-node-tunnel (GET /nodes/qits-node-tunnel serves it);
#   - `companion`, a separate final target (scratch or distroless static, uid 65532) holding
#     /qits-access-companion only, released as qits/qits-network-access-companion.
# Until then this file builds the service image alone, which is what release.yml declares.

ARG QITS_DOMAIN=wohlben.eu

# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK.
FROM mirror.qits.${QITS_DOMAIN}/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# The Maven distribution first, keyed only on the wrapper files.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor (`.dockerignore` keeps it cheap): `service` builds `domain` from source.
COPY . .

# -Dnative flips quarkus.native.enabled (the `native` profile in service/pom.xml). Tests are skipped:
# `./mvnw verify` on a clone is their gate, and stopping at `package` keeps the packaged ITs out of a
# stage that has no postgres to give them. The secrets are file mounts read into this RUN's
# environment alone — never a layer, never the image history.
#
# cyclonedx.skipNotDeployed=false is LOAD-BEARING: the service module is not deployed as a jar, and
# the plugin skips such modules by default with a green build, so the sbom stage's COPY would fail.
ARG QITS_DOMAIN=wohlben.eu
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)" \
    QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)" \
    QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven" \
    QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central" \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven" \
      -Dquarkus.native.native-image-xmx=4g \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false

# --- sbom -----------------------------------------------------------------------------------------
# Lifted out by the release step with `--opt target=sbom --output type=local`, with the same args and
# secrets as the image build, so every layer above is a cache hit.
FROM scratch AS sbom
COPY --from=build /src/service/target/sbom.json /sbom.json

# --- runtime --------------------------------------------------------------------------------------
FROM mirror.qits.${QITS_DOMAIN}/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# curl, so the container answers the deployer's docker healthcheck. UBI minimal supplies
# curl-minimal; assert it rather than install the conflicting full package.
RUN curl --version >/dev/null

# `quarkus.package.output-name` names the binary `qits-network-access`; spelled again as failsafe's
# native.image.path in service/pom.xml. The three move together.
COPY --from=build --chown=1001:root /src/service/target/qits-network-access /work/qits-network-access
RUN chmod +x /work/qits-network-access

# Nothing to own: every durable fact is a row in the database.
EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it.
ENTRYPOINT ["/work/qits-network-access", "-Dquarkus.http.host=0.0.0.0"]
