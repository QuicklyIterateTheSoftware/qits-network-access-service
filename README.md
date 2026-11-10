# qits-network-access-service

Who may reach the platform's **private edge**, and as which tunnel address. This service owns the
WireGuard peers — a person's devices and the qits-system targets (nodes) that joined — allocates
their addresses in the tunnel network, renders their configs, and announces every registration and
revocation on qits-events, which `qits-edge-private` applies to its `wg0` live.

The application is `qits-network-access` (wire alias `dev-qits-network-access`), image
`qits/qits-network-access`, served at `https://network-access.qits.<domain>/network-access`, API
under `/network-access/api`. Epic qits-1977; the design pages are on that epic's dossier
(`design-names-and-values`, `qits-network-access-service`).

```
 landing pane / qits CLI ──HTTPS──▶ public edge ──▶ qits-network-access (PUBLIC)
                                                    │ owns na_peer / na_node / na_private_edge
                                                    │ publishes PeerRegistered, PeerRevoked
                                                    ▼
                                                qits-events ──▶ qits-edge-private (wg0 100.96.0.1/24)
```

## Why it is public

There is no `exposure:` in `.config/qits/deployments.yml`, so the deployment is `public`, on
purpose: the place that grants access to the private edge must survive a private-edge fault. Every
door still takes a bearer; there is no anonymous door (`/network-access/q/health` is the platform's
own).

## The tunnel

| What | Value |
|---|---|
| Tunnel CIDR | `100.96.0.0/24` (`qits.network-access.tunnel.cidr`) |
| Private edge, also the tunnel DNS server | `100.96.0.1` |
| Peer addresses | `100.96.0.10`–`100.96.0.254` (`qits.network-access.tunnel.first-host=10`); `.2`–`.9` reserved |
| Peer side | `AllowedIPs = 100.96.0.1/32`, `PersistentKeepalive = 25` |
| Split DNS | the zone `${QITS_DOMAIN}`, as `~wohlben.eu` |

The endpoint, port, MTU and the edge's key are whatever the private edge **announced**
(`PrivateEdgeAnnounced`, stored in `na_private_edge`); nothing about the edge is configured here.

**Allocation** (`PeerAddresses`): the lowest address of the pool no ACTIVE peer holds, read inside
the insert's transaction. Two registrations racing for one address are settled by the partial unique
index `na_peer_active_tunnel_ip`; the loser is retried in a new transaction (`PeerRegistry`). A full
pool (245 ACTIVE peers) is `409 the tunnel address pool is exhausted`. A revoked peer's address is
free again at once.

## The data

`domain/src/main/resources/db/networkaccess/migration/V1__network_access.sql`, diagram in
`docs/database/`:

- `na_peer` — one WireGuard peer, DEVICE or NODE. Never deleted: revocation flips `status` to
  REVOKED with `revoked_at`, `revoked_by` and `revoke_reason` (REVOKED, USER_DELETED, NODE_DELETED,
  REPLACED). Among ACTIVE rows a public key, a tunnel address and a node's peer are unique, and so
  is a device's name per owner.
- `na_node` — a qits-system target that joined, keyed by the target's id.
- `na_private_edge` — what each private edge last announced about itself.

A device's **owner** is the caller's `sub`: for a person, the idp user id that `UserDeleted` names;
for a desk or a service, that client's id, which no `UserDeleted` matches (revoked by hand only).
**No private key ever reaches this service**: a device generates its key where it is used.

## The doors (`/network-access/api`)

| Door | Answer | Roles |
|---|---|---|
| `GET /peers?kind=&status=&owner=` | the peers, newest first; `owner=me` is the caller's `sub` | read |
| `GET /peers/{id}` | one peer | read |
| `POST /peers` `{name, publicKey}` | 201, a DEVICE peer owned by the caller; 400 bad name/key; 409 duplicate key, name, or full pool | write |
| `POST /peers/{id}/revoke` | 200, the peer REVOKED (reason REVOKED); idempotent | write |
| `GET /peers/{id}/config` | `text/plain`, the `wg-quick` config; 409 until an edge announced itself | read |
| `GET /peers/{id}/tunnel` | `{peerId, tunnelIp, edge: {publicKey, publicAddress, listenPort, dnsAddress, tunnelCidr, mtu, domain}}`, the companion's input | read |
| `GET /nodes`, `GET /nodes/{targetId}` | joined nodes with their ACTIVE peer | read |
| `POST /nodes/{targetId}/join` `{name, publicKey}` | 200, the node's tunnel answer (as `/peers/{id}/tunnel`); 403 another target's token; 409 no edge yet, a key another peer holds, or a full pool; 503 qits-system unreachable | write, or `qits:target-runner-registration` (checked) |
| `GET /nodes/qits-node-tunnel` | `application/octet-stream`, the static helper a node's install line runs | read, or `qits:target-runner-registration` |
| `GET /companion` | `{image: "registry.qits.<domain>/qits/qits-network-access-companion", version}`, this service's version | read |
| `GET /private-edge` | the announced edge, or 404 | read |

- **read**: `qits:admin`, `qits:admin-agent`, `qits:desk-admin`, `qits:desk`, `qits:agent`,
  `qits:system`. A config holds no secret, so it is a read.
- **write**: the same without `qits:agent` (403).

**The node join.** A node's registration token (`qits:target-runner-registration`) is asked about at
qits-system — `POST /system/api/targets/{id}/registration-check {subject: <the token's sub>}`, with
this service's own `qits` client bearer (`system/RegistrationCheck`) — and only a 200 naming this
very target admits it; qits-system's target name then names the node. The write set skips the check
(an operator's repair) and names the node by the body. Then: the node row is upserted; an ACTIVE
peer of the node under the same key is answered unchanged (a re-run of the install line); otherwise
the old peer is revoked `REPLACED` and a new NODE peer registered, keeping the node's last address
when it is free. Nothing is written before a private edge announced itself (409).

Names are DNS labels (`^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$`); a public key is 44 characters of
base64 decoding to 32 bytes.

**The config** (`PeerConfig.render`) has no `DNS =` line, deliberately: wg-quick would send every
query to the tunnel resolver, which refuses names outside the zone, and the client would lose the
internet. Split DNS is set with `resolvectl` in `PostUp`. `<PRIVATE_KEY>` is literal; the client
replaces it.

## The events

Published through `QitsEventBus` in the transaction that writes the row, after its flush:

| Event | Payload |
|---|---|
| `PeerRegistered` | `peerId, kind, peerName, publicKey, tunnelIp, ownerUserId` (DEVICE), `targetId` (NODE) |
| `PeerRevoked` | `peerId, kind, peerName, publicKey, tunnelIp, reason` (a re-join's `REPLACED` precedes its `PeerRegistered`) |

`eventId` and `occurredAt` travel in the envelope. The peer's label is `peerName`, never `name`:
that is `QitsEvent.name()`, the event's own name, and the library hides it from every payload. Consumers decode private wire DTOs; there is no
shared events jar.

## The consumers

Durable listeners (`bus/`), each replaying from the epoch, selecting only frames of this service's
own environment (`qits.network-access.environment`, the deployer's `QITS_ENVIRONMENT`), idempotent
under replay, and settling a frame they cannot read with a WARN instead of throwing:

| consumerId | Event (publisher) | Effect |
|---|---|---|
| `network-access-private-edge` | `PrivateEdgeAnnounced` (qits-edge-private) | upsert `na_private_edge` by `application`, only when the frame's `occurredAt` is newer |
| `network-access-user-deleted` | `UserDeleted` (qits-idp) | revoke every ACTIVE DEVICE peer whose owner is `userId`, `USER_DELETED`, by `qits-idp` |
| `network-access-target-deleted` | `TargetDeleted` (qits-system) | revoke the target's ACTIVE NODE peer, `NODE_DELETED`, by `qits-system`; set `na_node.deleted_at` |

Each decodes a private wire DTO. The payload carries neither `eventId` nor `occurredAt` (both are on
the frame) and no field called `name`.

## The node helper (`access-client/`)

A Go module (go 1.25, `golang.zx2c4.com/wireguard` at the commit of tag `0.0.20250522`, the edge's
wireguard-go) built by `docker/Dockerfile`'s `access-client` stage into a static binary the service
image carries at `/work/node-tunnel/qits-node-tunnel`:

- `qits-node-tunnel join --url <base> --target <id> [--name <host>]`, as root, with the token in
  `QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN`: keeps its key in `/etc/qits/node-tunnel/private.key`,
  joins, writes `tunnel.json` and the systemd unit `qits-node-tunnel.service`, and enables it. Exit
  `0` joined, `2` usage, `3` refused or unreachable, `4` no systemd, `1` anything else.
- `qits-node-tunnel up` (the unit's `ExecStart`): TUN `qits0`, wireguard-go in-process, the address
  and the one route by netlink, split DNS with `resolvectl` when systemd-resolved runs.

Shared code is in `access-client/internal/` (the key file, the tunnel JSON, the UAPI text and the
handshake read), and the companion below builds beside it.

## The companion (`access-client/cmd/qits-access-companion`)

A person's way onto the private edge from any machine with Docker: no TUN, no `NET_ADMIN`, no
`wg-quick`, no host DNS change. The image `qits/qits-network-access-companion` (docker/Dockerfile's
`companion` target: `FROM scratch`, the static binary alone, uid 65532, no CA certificates — it
terminates no TLS) is released with this service at the **same version**, which `GET /companion`
answers. `qits network-access up` runs it:

    docker run --rm -v qits-network-access-companion:/state <image>:<version> keygen      # prints the public key
    docker run --rm -i -v qits-network-access-companion:/state <image>:<version> configure < tunnel.json
    docker run -d --name qits-network-access-companion --restart unless-stopped \
      -p 127.0.0.1:47813:47813 -v qits-network-access-companion:/state <image>:<version> run

- **`keygen`** creates `/state/private.key` (0600) if it is absent and prints the base64 **public**
  key; it never prints the private one, and a second call prints the same key. An unreadable key
  file is an error, never replaced.
- **`configure`** reads the `GET /peers/{id}/tunnel` answer on stdin, validates it
  (`internal/tunnel`) and writes `/state/tunnel.json`.
- **`run`** brings up wireguard-go on gVisor's netstack (`golang.zx2c4.com/wireguard/tun/netstack`,
  the same wireguard-go commit as the node helper; gVisor at the version that commit requires):
  `Address = tunnelIp`, the edge as the only peer at `publicAddress:listenPort`, `AllowedIPs =
  dnsAddress/32`, the answer's MTU, keepalive 25. In front of it, on `0.0.0.0:47813` (docker
  publishes it to `127.0.0.1` only):

| Request | Answer |
|---|---|
| `CONNECT host:443` / `host:80`, `host` in the zone (`edge.domain`) | resolved by asking `dnsAddress:53` **through the tunnel** (UDP, TCP on truncation); an answer inside `tunnelCidr` is dialled through the tunnel (`route=tunnel`), any other answer directly from the container by that address (`route=direct`). A name the tunnel cannot resolve is 502 — never retried through the container's resolver |
| `CONNECT host:443` / `host:80`, any other host | dialled directly, through the container's own resolver (`route=direct`) |
| `CONNECT` an IP literal | through the tunnel when inside `tunnelCidr`, else directly |
| `CONNECT` any other port, any other method, an absolute-form (forward-proxy) request | 405, `Allow: CONNECT` — this is no open proxy |
| `GET /healthz` | `{peerId, tunnelIp, lastHandshake, endpoint}`; 200 when the device's last handshake (`IpcGet`'s `last_handshake_time_*`) is within 3 minutes, else 503 (`lastHandshake: null` before the first) |

One log line per request — `CONNECT host=<host:port> route=tunnel|direct status=<code>` — and
never a key. An upstream that cannot be dialled is 502.

The tests (`go test ./...`) cover the routing with a fake resolver and fake dialers (in-zone tunnel
answer, in-zone public answer, out of zone, IP literals, an unresolvable in-zone name), the 405
refusals, the healthz states, keygen persistence and `configure`'s validation, plus one end-to-end
test with two in-process netstack devices over loopback UDP: a stand-in edge at `100.96.0.1`
answering DNS and echoing on `:443`, and the companion's own device.

**Released by this repository's own `release:` slot** (`.config/qits/release.yml`): java-service's
publish step pushes exactly one image, so the slot pushes both — the service (untargeted) and
`--opt target=companion` — then lifts both SBOMs (`target=sbom`, `target=companion-sbom`, the latter
written by `cyclonedx-gomod` from the binary's build info). QA is still the archetype's.

## Building

`./mvnw verify` on a clone alone, with the platform Maven repository reachable. No docker: the suite
spawns real postgres binaries (zonky) as a child process. See `AGENTS.md`.
