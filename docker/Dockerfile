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
# THERE IS A PAGE, and it is NOT built here: it arrives in the build context already built. Quinoa
# serves service/src/main/webui (the qits-network-access-frontend submodule) at `/`; the java-service
# CI recipe initialises the submodule and runs `npm ci && npm run build` in it before this image is
# built, so a host-side build needs `git submodule update --init` and the same two commands first.
# The why is with the mvnw line below.
#
# THE NODE HELPER is built here too: the `access-client` stage compiles access-client/ (a Go module,
# not part of the Maven reactor) into a static qits-node-tunnel, and the runtime stage carries it at
# /work/node-tunnel/qits-node-tunnel, which GET /nodes/qits-node-tunnel serves to a node's install
# line.
#
# THE COMPANION rides in this same image: the `access-client` stage builds qits-access-companion
# too, and the runtime stage carries it at /work/companion/qits-access-companion with an empty
# /state (uid 65532, 0700). The release platform deploys one image per application, named for it,
# so the companion is not a second image. The CLI runs it as
#
#     docker run --user 65532:65532 --entrypoint /work/companion/qits-access-companion \
#       -v qits-network-access-companion:/state <this image>:<version> run
#
# which GET /companion answers (image, version, entrypoint, user).

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
#
# --- node, and the three command overrides: why the page is NOT built here --------------------------
# The two `package-manager-install` flags give this stage a node: the Mandrel builder image ships
# none, and Quinoa shells out to npm regardless. They live here rather than in
# application.properties on purpose — a local or CI `mvnw` build must use the node on PATH, so that no
# build silently downloads a toolchain. The version is the platform pin (22.22.0); it need not match
# the step container's node, because this stage does not build the bundle.
#
# The frontend depends on @qits/* packages that exist ONLY on the platform's own npm registry, which
# answers 401 without a credential, and this stage is handed only the Maven one. So the install
# cannot happen here: the bundle is built by the CI step before this image and arrives in the
# context as service/src/main/webui/dist/ (.dockerignore stops short of excluding it). `--version` is
# the null command: it proves the package manager is there and does nothing else.
#
# THIS DOES NOT FAIL QUIETLY. The `test -f` below stops a context with no bundle — a forgotten build,
# or a webui submodule that was never initialised — before the multi-minute native compile.
#
# --- and why the bundle is copied onto itself first -------------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename a
# directory that still lives in a LOWER image layer (EXDEV; the JDK's fallback then refuses a
# non-empty directory with DirectoryNotEmptyException). One `cp` re-materialises it in the layer that
# is about to move it, so it has to be in THIS `RUN`, not one of its own.
ARG QITS_DOMAIN=wohlben.eu
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    export QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)"; \
    export QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)"; \
    export QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven"; \
    export QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central"; \
    d=service/src/main/webui/dist/qits-network-access-frontend/browser; \
    test -f "$d/index.html" || { \
      echo "No built page at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version \
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
 && go build -trimpath -ldflags '-s -w' -o /out/qits-access-companion ./cmd/qits-access-companion

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

# The node helper GET /nodes/qits-node-tunnel serves (qits.network-access.node-tunnel.path). The
# path moves together with that property.
COPY --from=access-client --chown=1001:root /out/qits-node-tunnel /work/node-tunnel/qits-node-tunnel

# The companion, which the CLI runs from this image with its own entrypoint and user (GET
# /companion; qits.network-access.companion.* in application.properties — the two move together).
# /state is created here, empty, owned by that uid and 0700, so the named volume docker creates on
# first mount inherits both and the unprivileged companion can write its key there. The service
# itself runs as 1001 and never touches /state.
COPY --from=access-client --chown=1001:root /out/qits-access-companion /work/companion/qits-access-companion
RUN mkdir -m 0700 /state && chown 65532:65532 /state

# Nothing to own: every durable fact is a row in the database.
EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it.
ENTRYPOINT ["/work/qits-network-access", "-Dquarkus.http.host=0.0.0.0"]
