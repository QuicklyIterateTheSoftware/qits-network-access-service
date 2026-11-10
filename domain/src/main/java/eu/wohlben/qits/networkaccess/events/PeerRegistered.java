package eu.wohlben.qits.networkaccess.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * A peer became ACTIVE: the private edge adds it ({@code PeerRegistered}). Published by {@code
 * PeerRegistry} in the transaction that inserts the row.
 *
 * <p>Payload fields, in the design table's order: {@code peerId, kind (DEVICE|NODE), peerName,
 * publicKey (base64, 44 chars), tunnelIp, ownerUserId (DEVICE, else null), targetId (NODE, else
 * null)}. {@code eventId} and {@code occurredAt} are the {@link QitsEvent} accessors: the library
 * hides them from the payload and carries them on the envelope.
 *
 * <p><b>{@code peerName}, never {@code name}.</b> {@code name()} is a {@link QitsEvent} method — the
 * event's NAME on the envelope — and qits-eventstream's {@code CanonicalJson} mix-in marks it
 * {@code @JsonIgnore}, so a component called {@code name} would both rename the event after the
 * peer and vanish from the payload. qits-edge-private reads {@code peerName} (qits-2020). {@code
 * PeerEventPayloadTest} pins it.
 */
@RegisterForReflection
public record PeerRegistered(
    UUID eventId,
    UUID peerId,
    String kind,
    String peerName,
    String publicKey,
    String tunnelIp,
    String ownerUserId,
    UUID targetId,
    Instant occurredAt)
    implements QitsEvent {}
