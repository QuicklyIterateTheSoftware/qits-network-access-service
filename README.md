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
| `GET /companion` | `{image: "registry.qits.<domain>/qits/qits-network-access-companion", version}`, this service's version | read |
| `GET /private-edge` | the announced edge, or 404 | read |

- **read**: `qits:admin`, `qits:admin-agent`, `qits:desk-admin`, `qits:desk`, `qits:agent`,
  `qits:system`. A config holds no secret, so it is a read.
- **write**: the same without `qits:agent` (403).

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
| `PeerRevoked` | `peerId, kind, peerName, publicKey, tunnelIp, reason` |

`eventId` and `occurredAt` travel in the envelope. The peer's label is `peerName`, never `name`:
that is `QitsEvent.name()`, the event's own name, and the library hides it from every payload. Consumers decode private wire DTOs; there is no
shared events jar.

## Still to come (the design's later sections)

- **Consumers** (section 4): durable listeners for `PrivateEdgeAnnounced` (upsert
  `na_private_edge`), `UserDeleted` (revoke the owner's devices, USER_DELETED) and `TargetDeleted`
  (revoke the node's peer, NODE_DELETED).
- **Node join** (section 5): `POST /nodes/{targetId}/join`, checked against qits-system's
  registration-check door, and `GET /nodes/qits-node-tunnel`, the static Go helper from
  `access-client/`.
- **The companion** (section 6): `qits/qits-network-access-companion`, a netstack WireGuard client
  with an HTTP CONNECT proxy, built from this repository beside the service image.

## Building

`./mvnw verify` on a clone alone, with the platform Maven repository reachable. No docker: the suite
spawns real postgres binaries (zonky) as a child process. See `AGENTS.md`.
