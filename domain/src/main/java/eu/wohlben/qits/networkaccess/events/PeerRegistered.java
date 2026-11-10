package eu.wohlben.qits.networkaccess.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import eu.wohlben.qits.eventstream.QitsEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * A peer became ACTIVE: the private edge adds it ({@code PeerRegistered}). Published by {@code
 * PeerRegistry} in the transaction that inserts the row.
 *
 * <p>Payload fields, as the design page lists them: {@code peerId, kind (DEVICE|NODE), name,
 * publicKey (base64, 44 chars), tunnelIp, ownerUserId (DEVICE, else null), targetId (NODE, else
 * null)}; {@code eventId} and {@code occurredAt} travel in the envelope.
 *
 * <p><b>The peer's name is the component {@code peerName}, on the wire as {@code name}.</b> A
 * component called {@code name} would override {@link QitsEvent#name()} — the event's NAME on the
 * wire — and the library's mix-in hides that accessor from the payload, so the event would go out
 * named after the peer and without the field. {@code PeerEventPayloadTest} pins both.
 */
@RegisterForReflection
public record PeerRegistered(
    UUID eventId,
    UUID peerId,
    String kind,
    @JsonProperty("name") String peerName,
    String publicKey,
    String tunnelIp,
    String ownerUserId,
    UUID targetId,
    Instant occurredAt)
    implements QitsEvent {}
