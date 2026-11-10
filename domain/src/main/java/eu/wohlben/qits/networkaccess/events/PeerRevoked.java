package eu.wohlben.qits.networkaccess.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * A peer stopped being ACTIVE: the private edge removes it ({@code PeerRevoked}). Published by
 * {@code PeerRegistry} in the transaction that revokes the row; a node's re-join publishes it
 * ({@code REPLACED}) before the new {@code PeerRegistered}.
 *
 * <p>Payload fields: {@code peerId, kind, peerName, publicKey, tunnelIp, reason
 * (REVOKED|USER_DELETED|NODE_DELETED|REPLACED)}; {@code eventId} and {@code occurredAt} ride the
 * envelope, and the peer's label is {@code peerName} for the reason {@link PeerRegistered} gives.
 */
@RegisterForReflection
public record PeerRevoked(
    UUID eventId,
    UUID peerId,
    String kind,
    String peerName,
    String publicKey,
    String tunnelIp,
    String reason,
    Instant occurredAt)
    implements QitsEvent {}
