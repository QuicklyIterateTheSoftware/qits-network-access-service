-- qits-network-access, the first lineage: the nodes that joined, the WireGuard peers (devices and
-- nodes) and the private edges that announced themselves.

-- A node is a qits-system target that joined the tunnel. Keyed by the target's id: node identity
-- stays with qits-system, this row only remembers that it joined and under which name.
create table na_node (
    target_id      uuid primary key,
    name           text not null,
    created_at     timestamptz not null,
    last_joined_at timestamptz,
    deleted_at     timestamptz
);

-- One WireGuard peer. A row is never deleted: revocation flips status and keeps the history, so the
-- uniqueness that matters (one key, one address, one node peer) holds among ACTIVE rows only.
create table na_peer (
    id            uuid primary key,
    kind          text not null check (kind in ('DEVICE', 'NODE')),
    name          text not null,
    public_key    text not null,
    tunnel_ip     inet not null,
    owner_user_id text,
    target_id     uuid references na_node (target_id),
    status        text not null check (status in ('ACTIVE', 'REVOKED')),
    created_at    timestamptz not null,
    created_by    text not null,
    revoked_at    timestamptz,
    revoked_by    text,
    revoke_reason text check (revoke_reason in ('REVOKED', 'USER_DELETED', 'NODE_DELETED', 'REPLACED'))
);

-- The guards the registry relies on. The tunnel address one is also the allocation's lock: two
-- registrations that picked the same lowest free address race here, and the loser retries.
create unique index na_peer_active_public_key on na_peer (public_key) where status = 'ACTIVE';
create unique index na_peer_active_tunnel_ip on na_peer (tunnel_ip) where status = 'ACTIVE';
create unique index na_peer_active_node on na_peer (target_id) where status = 'ACTIVE' and kind = 'NODE';
-- A device's name is unique among its owner's ACTIVE peers.
create unique index na_peer_active_owner_name on na_peer (owner_user_id, name)
    where status = 'ACTIVE' and owner_user_id is not null;
create index na_peer_created_at on na_peer (created_at desc);

-- What a private edge announced about itself (PrivateEdgeAnnounced), one row per edge application,
-- kept only when the announcement is newer. Written by the consumer of section 4.
create table na_private_edge (
    application    text primary key,
    public_key     text not null,
    public_address text not null,
    listen_port    integer not null,
    tunnel_address text not null,
    tunnel_cidr    text not null,
    dns_address    text not null,
    mtu            integer not null,
    domain         text not null,
    announced_at   timestamptz not null,
    event_id       uuid not null
);
