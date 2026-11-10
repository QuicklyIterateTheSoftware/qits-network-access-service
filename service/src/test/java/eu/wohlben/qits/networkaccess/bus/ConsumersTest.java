package eu.wohlben.qits.networkaccess.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.networkaccess.entity.NaNode;
import eu.wohlben.qits.networkaccess.entity.NaPeer;
import eu.wohlben.qits.networkaccess.entity.NaPrivateEdge;
import eu.wohlben.qits.networkaccess.events.PeerRevoked;
import eu.wohlben.qits.networkaccess.support.Fixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three durable consumers against the real store, fed frames shaped as the library delivers
 * them: the payload without {@code eventId} and {@code occurredAt} (both on the frame), without any
 * {@code name} field and without nulls. Each is idempotent under replay, selects only this
 * environment's frames, and settles a poison frame rather than throwing.
 */
@QuarkusTest
class ConsumersTest {

  static final String ENVIRONMENT = "dev";

  static final String OWNER = "7c0d9e8a-1b2c-4d3e-8f4a-5b6c7d8e9f00";

  static final UUID TARGET = UUID.fromString("4f2d9c71-8a3e-4b5f-9d6c-7e8f9a0b1c2d");

  @Inject Fixtures fixtures;

  @Inject PrivateEdgeAnnouncedListener edges;

  @Inject UserDeletedListener users;

  @Inject TargetDeletedListener targets;

  List<QitsEvent> events;

  @BeforeEach
  void clean() {
    fixtures.clear();
    events = Fixtures.recordEvents();
  }

  // --- PrivateEdgeAnnounced ---------------------------------------------------------------------

  private static JsonObject announcement(String address) {
    return new JsonObject()
        .put("application", "qits-edge-private")
        .put("publicKey", Fixtures.EDGE_PUBLIC_KEY)
        .put("publicAddress", address)
        .put("listenPort", 47812)
        .put("tunnelAddress", "100.96.0.1")
        .put("tunnelCidr", "100.96.0.0/24")
        .put("dnsAddress", "100.96.0.1")
        .put("mtu", 1380)
        .put("domain", "wohlben.eu");
  }

  private static NaPrivateEdge storedEdge() {
    return QuarkusTransaction.requiringNew().call(() -> NaPrivateEdge.findById("qits-edge-private"));
  }

  @Test
  void anAnnouncementIsStoredWithTheFramesIdAndTime() {
    EventFrame frame =
        frame("PrivateEdgeAnnounced", Instant.parse("2026-11-10T08:00:00.123456789Z"), announcement("46.224.171.33"));
    assertTrue(edges.selects(frame));
    edges.onFrame(frame);
    NaPrivateEdge edge = storedEdge();
    assertNotNull(edge);
    assertEquals("46.224.171.33", edge.publicAddress);
    assertEquals(47812, edge.listenPort);
    assertEquals(1380, edge.mtu);
    assertEquals("wohlben.eu", edge.domain);
    assertEquals(UUID.fromString(frame.id()), edge.eventId);
    assertEquals(Instant.parse("2026-11-10T08:00:00.123456Z"), edge.announcedAt);
    // The replay of the very same frame changes nothing.
    edges.onFrame(frame);
    assertEquals(UUID.fromString(frame.id()), storedEdge().eventId);
  }

  @Test
  void onlyANewerAnnouncementReplacesTheStoredOne() {
    edges.onFrame(frame("PrivateEdgeAnnounced", Instant.parse("2026-11-10T09:00:00Z"), announcement("10.0.0.2")));
    edges.onFrame(frame("PrivateEdgeAnnounced", Instant.parse("2026-11-10T08:00:00Z"), announcement("10.0.0.1")));
    assertEquals("10.0.0.2", storedEdge().publicAddress, "an older frame arriving late is ignored");
    edges.onFrame(frame("PrivateEdgeAnnounced", Instant.parse("2026-11-10T10:00:00Z"), announcement("10.0.0.3")));
    assertEquals("10.0.0.3", storedEdge().publicAddress);
  }

  @Test
  void anIncompleteOrUnreadableAnnouncementIsSettledUnhandled() {
    JsonObject missing = announcement("10.0.0.1");
    missing.remove("listenPort");
    edges.onFrame(frame("PrivateEdgeAnnounced", Instant.now(), missing));
    edges.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(), "PrivateEdgeAnnounced", Instant.now(), "not json", null, null, ENVIRONMENT));
    assertNull(storedEdge());
  }

  @Test
  void anotherEnvironmentsFramesAreNotSelected() {
    EventFrame foreign =
        new EventFrame(
            UUID.randomUUID().toString(),
            "PrivateEdgeAnnounced",
            Instant.now(),
            announcement("10.0.0.1").encode(),
            null,
            null,
            "prod");
    assertFalse(edges.selects(foreign));
    assertFalse(users.selects(foreign));
    assertFalse(targets.selects(foreign));
  }

  // --- UserDeleted ------------------------------------------------------------------------------

  @Test
  void aDeletedUsersActiveDevicesAreRevokedOnce() {
    UUID laptop = UUID.randomUUID();
    UUID phone = UUID.randomUUID();
    UUID someoneElse = UUID.randomUUID();
    fixtures.device(laptop, "laptop", Fixtures.key(2), "100.96.0.10", OWNER);
    fixtures.device(phone, "phone", Fixtures.key(3), "100.96.0.11", OWNER);
    fixtures.device(someoneElse, "laptop", Fixtures.key(4), "100.96.0.12", "another-owner");
    EventFrame frame =
        frame(
            "UserDeleted",
            Instant.now(),
            new JsonObject().put("userId", OWNER).put("username", "alice"));
    assertTrue(users.selects(frame));
    users.onFrame(frame);

    for (UUID id : List.of(laptop, phone)) {
      NaPeer peer = peer(id);
      assertEquals("REVOKED", peer.status.name());
      assertEquals("USER_DELETED", peer.revokeReason.name());
      assertEquals("qits-idp", peer.revokedBy);
    }
    assertEquals("ACTIVE", peer(someoneElse).status.name());
    List<PeerRevoked> revoked = Fixtures.of(events, PeerRevoked.class);
    assertEquals(2, revoked.size(), "one PeerRevoked per device: " + events);
    assertTrue(revoked.stream().allMatch(e -> e.reason().equals("USER_DELETED")));

    users.onFrame(frame);
    assertEquals(2, events.size(), "the replay publishes nothing: " + events);
  }

  @Test
  void aUserDeletedWithoutAUserIdIsSettledUnhandled() {
    fixtures.device(UUID.randomUUID(), "laptop", Fixtures.key(2), "100.96.0.10", OWNER);
    users.onFrame(frame("UserDeleted", Instant.now(), new JsonObject().put("username", "alice")));
    users.onFrame(frame("UserDeleted", Instant.now(), new JsonObject().put("userId", "nope")));
    assertEquals(0, events.size());
  }

  // --- TargetDeleted ----------------------------------------------------------------------------

  @Test
  void aDeletedTargetsNodePeerIsRevokedAndTheNodeMarkedDeleted() {
    UUID peerId = UUID.randomUUID();
    fixtures.node(TARGET, "lab", peerId, Fixtures.key(4), "100.96.0.11");
    EventFrame frame =
        frame(
            "TargetDeleted",
            Instant.now(),
            new JsonObject().put("targetId", TARGET.toString()).put("targetName", "lab"));
    assertTrue(targets.selects(frame));
    targets.onFrame(frame);

    NaPeer peer = peer(peerId);
    assertEquals("REVOKED", peer.status.name());
    assertEquals("NODE_DELETED", peer.revokeReason.name());
    assertEquals("qits-system", peer.revokedBy);
    NaNode node = QuarkusTransaction.requiringNew().call(() -> NaNode.findById(TARGET));
    assertNotNull(node.deletedAt);
    assertEquals(1, events.size(), "exactly one PeerRevoked: " + events);

    Instant deleted = node.deletedAt;
    targets.onFrame(frame);
    assertEquals(1, events.size(), "the replay publishes nothing: " + events);
    assertEquals(
        deleted, QuarkusTransaction.requiringNew().call(() -> NaNode.<NaNode>findById(TARGET).deletedAt));
  }

  @Test
  void aTargetThatNeverJoinedIsNothingHere() {
    targets.onFrame(
        frame("TargetDeleted", Instant.now(), new JsonObject().put("targetId", TARGET.toString())));
    targets.onFrame(frame("TargetDeleted", Instant.now(), new JsonObject().put("targetId", "x")));
    assertEquals(0, events.size());
  }

  private static NaPeer peer(UUID id) {
    return QuarkusTransaction.requiringNew().call(() -> NaPeer.findById(id));
  }

  private static EventFrame frame(String name, Instant at, JsonObject payload) {
    return new EventFrame(
        UUID.randomUUID().toString(), name, at, payload.encode(), null, null, ENVIRONMENT);
  }
}
