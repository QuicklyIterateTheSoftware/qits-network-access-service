package eu.wohlben.qits.networkaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.networkaccess.events.PeerRegistered;
import eu.wohlben.qits.networkaccess.events.PeerRevoked;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two events as qits-eventstream puts them on the wire: the envelope's NAME is the record's
 * simple name, and the payload holds exactly the design table's fields, the peer's label as {@code
 * peerName} — never {@code name}, which is {@code QitsEvent.name()}, the event's name, and which the
 * library's mix-in hides from every payload. {@code eventId} and {@code occurredAt} are on the
 * envelope, never in the payload.
 */
class PeerEventPayloadTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void peerRegisteredIsNamedForItsTypeAndCarriesTheDesignFields() throws Exception {
    PeerRegistered event =
        new PeerRegistered(
            UUID.randomUUID(),
            UUID.fromString("0e6b4f39-6f0e-4a55-9a1e-0c3a1e7c2a11"),
            "DEVICE",
            "laptop",
            "KEY",
            "100.96.0.10",
            "owner",
            null,
            Instant.parse("2026-11-10T09:00:00Z"));
    assertEquals("PeerRegistered", event.name());
    JsonNode payload = JSON.readTree(CanonicalJson.payload(event));
    assertEquals(
        List.of("kind", "ownerUserId", "peerId", "peerName", "publicKey", "tunnelIp"),
        fields(payload));
    assertEquals("laptop", payload.get("peerName").asText());
    // A null component is left out of the canonical payload (the library includes non-nulls
    // only), which a consumer reads as null: a DEVICE carries no targetId on the wire.
    assertEquals(null, payload.get("targetId"));
  }

  @Test
  void peerRevokedIsNamedForItsTypeAndCarriesTheDesignFields() throws Exception {
    PeerRevoked event =
        new PeerRevoked(
            UUID.randomUUID(),
            UUID.fromString("0e6b4f39-6f0e-4a55-9a1e-0c3a1e7c2a11"),
            "NODE",
            "node-a",
            "KEY",
            "100.96.0.20",
            "REPLACED",
            Instant.parse("2026-11-10T09:00:00Z"));
    assertEquals("PeerRevoked", event.name());
    JsonNode payload = JSON.readTree(CanonicalJson.payload(event));
    assertEquals(
        List.of("kind", "peerId", "peerName", "publicKey", "reason", "tunnelIp"), fields(payload));
    assertEquals("node-a", payload.get("peerName").asText());
  }

  private static List<String> fields(JsonNode node) {
    List<String> out = new java.util.ArrayList<>();
    node.fieldNames().forEachRemaining(out::add);
    return out.stream().sorted().toList();
  }
}
