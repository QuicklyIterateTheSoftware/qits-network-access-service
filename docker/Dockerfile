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
# THE NODE HELPER is built here too: the `access-client` stage compiles access-client/ (a Go module,
# not part of the Maven reactor) into a static qits-node-tunnel, and the runtime stage carries it at
# /work/node-tunnel/qits-node-tunnel, which GET /nodes/qits-node-tunnel serves to a node's install
# line.
#
# THE COMPANION is a second image from this file: the same `access-client` stage builds
# qits-access-companion, and the `companion` target (FROM scratch, uid 65532) holds it alone. It is
# released as qits/qits-network-access-companion at the service's own version, which GET /companion
# answers. Build it with
#
#     docker build --target companion -t qits/qits-network-access-companion:latest -f docker/Dockerfile .
#
# The runtime stage stays LAST on purpose: an untargeted build — the java-service QA step's — is the
# service image, and still runs the access-client stage (vet, tests, both binaries, the companion's
# SBOM) because the service image copies the node helper out of it.

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

# --- stage `access-client`: the Go helpers ------------------------------------------------------
# The shape of qits-edge's `wireguard` stage (docker/edge-init): static (CGO_ENABLED=0), stripped,
# so the binary needs nothing from the node it runs on. go.sum pins every module, wireguard-go
# included, at the commit of tag 0.0.20250522 (f333402bd9cb), the edge's own. The downloads go to the
# internet (proxy.golang.org), as the edge's do; go.mod and go.sum are copied first, so the module
# download is a cached layer until they change. vet and the tests run before the build, so a red
# helper fails the image.
FROM mirror.qits.${QITS_DOMAIN}/hub/library/golang:1.25 AS access-client
ENV CGO_ENABLED=0
WORKDIR /src/access-client
COPY access-client/go.mod access-client/go.sum ./
RUN go mod download
COPY access-client/ ./
RUN go vet ./... \
 && go test ./... \
 && go build -trimpath -ldflags '-s -w' -o /out/qits-node-tunnel ./cmd/qits-node-tunnel \
 && go build -trimpath -ldflags '-s -w' -o /out/qits-access-companion ./cmd/qits-access-companion \
 && install -d -m 0700 /out/state
# The companion's CycloneDX document, out of the binary's own build info (`go version -m`), the Go
# standard library included. Here rather than in a stage of its own so the QA build — which builds
# this stage for the service image — proves the tool resolves before a release depends on it. The
# tool is pinned by version and checked against sum.golang.org like any module download.
RUN go install github.com/CycloneDX/cyclonedx-gomod/cmd/cyclonedx-gomod@v1.9.0 \
 && cyclonedx-gomod bin -json -std -noserial -output-version 1.6 \
      -output /out/sbom-companion.json /out/qits-access-companion

# --- sbom -----------------------------------------------------------------------------------------
# Lifted out by the release step with `--opt target=sbom --output type=local`, with the same args and
# secrets as the image build, so every layer above is a cache hit.
FROM scratch AS sbom
COPY --from=build /src/service/target/sbom.json /sbom.json

# --- companion-sbom -------------------------------------------------------------------------------
# The companion's document, lifted the same way with `--opt target=companion-sbom`.
FROM scratch AS companion-sbom
COPY --from=access-client /out/sbom-companion.json /sbom-companion.json

# --- companion ------------------------------------------------------------------------------------
# qits/qits-network-access-companion: the static binary and nothing else. No CA certificates — it
# terminates no TLS (a CONNECT tunnel carries the client's TLS end to end) and speaks to the edge in
# WireGuard over UDP. No shell, no passwd: the uid is numeric. /state is created here, owned by
# that uid and 0700, so the named volume docker creates on first mount inherits both and the
# unprivileged process can write its key into it.
FROM scratch AS companion
COPY --from=access-client --chown=65532:65532 /out/state /state
COPY --from=access-client /out/qits-access-companion /qits-access-companion
USER 65532:65532
EXPOSE 47813
ENTRYPOINT ["/qits-access-companion"]
CMD ["run"]

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

# The node helper GET /nodes/qits-node-tunnel serves (qits.network-access.node-tunnel.path). The
# path moves together with that property.
COPY --from=access-client --chown=1001:root /out/qits-node-tunnel /work/node-tunnel/qits-node-tunnel

# Nothing to own: every durable fact is a row in the database.
EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it.
ENTRYPOINT ["/work/qits-network-access", "-Dquarkus.http.host=0.0.0.0"]
