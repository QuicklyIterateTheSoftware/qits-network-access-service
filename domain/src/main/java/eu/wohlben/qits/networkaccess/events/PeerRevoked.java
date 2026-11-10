package eu.wohlben.qits.networkaccess.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import eu.wohlben.qits.eventstream.QitsEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * A peer stopped being ACTIVE: the private edge removes it ({@code PeerRevoked}). Published by
 * {@code PeerRegistry} in the transaction that revokes the row; a node's re-join publishes it
 * ({@code REPLACED}) before the new {@code PeerRegistered}.
 *
 * <p>Payload fields: {@code peerId, kind, name, publicKey, tunnelIp, reason
 * (REVOKED|USER_DELETED|NODE_DELETED|REPLACED)}; the peer's name is {@code peerName} on the record
 * for the reason {@link PeerRegistered} gives.
 */
@RegisterForReflection
public record PeerRevoked(
    UUID eventId,
    UUID peerId,
    String kind,
    @JsonProperty("name") String peerName,
    String publicKey,
    String tunnelIp,
    String reason,
    Instant occurredAt)
    implements QitsEvent {}
