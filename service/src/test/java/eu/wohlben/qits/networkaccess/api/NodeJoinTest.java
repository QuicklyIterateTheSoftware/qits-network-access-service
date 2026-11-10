package eu.wohlben.qits.networkaccess.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.networkaccess.control.NetworkAccessException;
import eu.wohlben.qits.networkaccess.control.PeerRegistry;
import eu.wohlben.qits.networkaccess.entity.NaNode;
import eu.wohlben.qits.networkaccess.entity.NaPeer;
import eu.wohlben.qits.networkaccess.events.PeerRegistered;
import eu.wohlben.qits.networkaccess.events.PeerRevoked;
import eu.wohlben.qits.networkaccess.support.Fixtures;
import eu.wohlben.qits.networkaccess.system.RegistrationCheck;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /nodes/{targetId}/join} end to end against the real store, with qits-system's
 * registration check stubbed ({@link RegistrationCheck}): only this target's registration token
 * joins it, an operator skips the check, a re-run with the same key is the same peer (200, no
 * event), a new key REPLACES the old peer and keeps its address, and nothing is written before a
 * private edge announced itself.
 */
@QuarkusTest
class NodeJoinTest {

  static final String NODES = "/network-access/api/nodes";

  static final UUID TARGET = UUID.fromString("00000000-0000-4000-8000-000000000001");

  /** The registration token's subject the stub accepts for {@link #TARGET}. */
  static final String TOKEN_SUBJECT = "contract-registration-subject";

  @Inject Fixtures fixtures;

  @Inject PeerRegistry registry;

  List<QitsEvent> events;

  /** Every (target, subject) the stub was asked about. */
  final List<String> asked = new ArrayList<>();

  @BeforeEach
  void arrange() {
    fixtures.clear();
    fixtures.edge();
    events = Fixtures.recordEvents();
    asked.clear();
    QuarkusMock.installMockForType(
        new RegistrationCheck() {
          @Override
          public String targetName(UUID targetId, String subject) {
            asked.add(targetId + " " + subject);
            if (TARGET.equals(targetId) && TOKEN_SUBJECT.equals(subject)) {
              return "lab";
            }
            throw NetworkAccessException.forbidden(RegistrationCheck.REFUSED);
          }
        },
        RegistrationCheck.class);
  }

  private static ValidatableResponse join(UUID target, String name, String key) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "publicKey", key))
        .post(NODES + "/" + target + "/join")
        .then();
  }

  @Test
  @TestSecurity(user = TOKEN_SUBJECT, roles = "qits:target-runner-registration")
  void thisTargetsRegistrationTokenJoinsItUnderQitsSystemsName() {
    String peerId =
        join(TARGET, "host-1", Fixtures.key(5))
            .statusCode(200)
            .body("tunnelIp", equalTo("100.96.0.10"))
            .body("edge.publicKey", equalTo(Fixtures.EDGE_PUBLIC_KEY))
            .body("edge.publicAddress", equalTo("46.224.171.33"))
            .body("edge.listenPort", equalTo(47812))
            .body("edge.dnsAddress", equalTo("100.96.0.1"))
            .body("edge.tunnelCidr", equalTo("100.96.0.0/24"))
            .body("edge.mtu", equalTo(1380))
            .body("edge.domain", equalTo("wohlben.eu"))
            .extract()
            .path("peerId");
    assertEquals(List.of(TARGET + " " + TOKEN_SUBJECT), asked);

    NaPeer peer = QuarkusTransaction.requiringNew().call(() -> NaPeer.findById(UUID.fromString(peerId)));
    assertEquals("NODE", peer.kind.name());
    assertEquals("lab", peer.name, "qits-system's target name, not the body's");
    assertEquals(TARGET, peer.targetId);
    assertNull(peer.ownerUserId);
    assertEquals(TOKEN_SUBJECT, peer.createdBy);
    NaNode node = QuarkusTransaction.requiringNew().call(() -> NaNode.findById(TARGET));
    assertEquals("lab", node.name);

    assertEquals(1, events.size(), "exactly one event: " + events);
    PeerRegistered registered = Fixtures.of(events, PeerRegistered.class).getFirst();
    assertEquals("NODE", registered.kind());
    assertEquals(TARGET, registered.targetId());
    assertNull(registered.ownerUserId());
    assertEquals("100.96.0.10", registered.tunnelIp());
  }

  @Test
  @TestSecurity(user = "another-subject", roles = "qits:target-runner-registration")
  void anotherTokenIsRefusedAndNothingIsWritten() {
    join(TARGET, "host-1", Fixtures.key(5))
        .statusCode(403)
        .body("message", equalTo(RegistrationCheck.REFUSED));
    assertEquals(0, events.size(), "a refused join publishes nothing: " + events);
    assertNull(QuarkusTransaction.requiringNew().call(() -> NaNode.findById(TARGET)));
  }

  @Test
  @TestSecurity(user = TOKEN_SUBJECT, roles = "qits:target-runner-registration")
  void theTokenOfOneTargetDoesNotJoinAnother() {
    UUID other = UUID.fromString("00000000-0000-4000-8000-000000000002");
    join(other, "host-1", Fixtures.key(5)).statusCode(403);
    assertEquals(List.of(other + " " + TOKEN_SUBJECT), asked);
    assertEquals(0, events.size());
  }

  @Test
  @TestSecurity(user = TOKEN_SUBJECT, roles = "qits:target-runner-registration")
  void aRerunWithTheSameKeyIsTheSamePeer() {
    String first = join(TARGET, "host-1", Fixtures.key(5)).statusCode(200).extract().path("peerId");
    events.clear();
    String second =
        join(TARGET, "host-1", Fixtures.key(5))
            .statusCode(200)
            .body("tunnelIp", equalTo("100.96.0.10"))
            .extract()
            .path("peerId");
    assertEquals(first, second);
    assertEquals(0, events.size(), "an idempotent re-join publishes nothing: " + events);
  }

  @Test
  @TestSecurity(user = TOKEN_SUBJECT, roles = "qits:target-runner-registration")
  void aNewKeyReplacesTheOldPeerAndKeepsItsAddress() {
    UUID old = UUID.randomUUID();
    fixtures.device(UUID.randomUUID(), "laptop", Fixtures.key(2), "100.96.0.10", "someone");
    fixtures.node(TARGET, "lab", old, Fixtures.key(3), "100.96.0.11");

    String peerId =
        join(TARGET, "host-1", Fixtures.key(5))
            .statusCode(200)
            .body("tunnelIp", equalTo("100.96.0.11"))
            .extract()
            .path("peerId");
    assertNotEquals(old.toString(), peerId);

    NaPeer replaced = QuarkusTransaction.requiringNew().call(() -> NaPeer.findById(old));
    assertEquals("REVOKED", replaced.status.name());
    assertEquals("REPLACED", replaced.revokeReason.name());
    assertEquals(TOKEN_SUBJECT, replaced.revokedBy);

    assertEquals(2, events.size(), "the revocation, then the registration: " + events);
    PeerRevoked revoked = (PeerRevoked) events.get(0);
    assertEquals(old, revoked.peerId());
    assertEquals("REPLACED", revoked.reason());
    PeerRegistered registered = (PeerRegistered) events.get(1);
    assertEquals(UUID.fromString(peerId), registered.peerId());
    assertEquals("100.96.0.11", registered.tunnelIp());
  }

  @Test
  @TestSecurity(user = TOKEN_SUBJECT, roles = "qits:target-runner-registration")
  void aDeletedNodeThatJoinsAgainGetsItsAddressBack() {
    fixtures.device(UUID.randomUUID(), "laptop", Fixtures.key(2), "100.96.0.10", "someone");
    fixtures.node(TARGET, "lab", UUID.randomUUID(), Fixtures.key(3), "100.96.0.11");
    registry.nodeDeleted(TARGET, "qits-system");

    join(TARGET, "host-1", Fixtures.key(5)).statusCode(200).body("tunnelIp", equalTo("100.96.0.11"));
    NaNode node = QuarkusTransaction.requiringNew().call(() -> NaNode.findById(TARGET));
    assertNull(node.deletedAt, "the join brought the node back");
  }

  @Test
  @TestSecurity(user = "the-admin", roles = "qits:admin")
  void theWriteSetSkipsTheCheckAndNamesTheNodeByTheBody() {
    join(TARGET, "repair-1", Fixtures.key(5)).statusCode(200);
    assertEquals(List.of(), asked, "an operator's join asks qits-system nothing");
    NaNode node = QuarkusTransaction.requiringNew().call(() -> NaNode.findById(TARGET));
    assertEquals("repair-1", node.name);
  }

  @Test
  @TestSecurity(user = "the-admin", roles = "qits:admin")
  void aKeyAnotherActivePeerHoldsIsAConflict() {
    fixtures.device(UUID.randomUUID(), "laptop", Fixtures.key(5), "100.96.0.10", "someone");
    join(TARGET, "repair-1", Fixtures.key(5)).statusCode(409);
    assertEquals(0, events.size());
  }

  @Test
  @TestSecurity(user = "the-admin", roles = "qits:admin")
  void aBadNameOrKeyIsRefused() {
    join(TARGET, "Not A Label", Fixtures.key(5)).statusCode(400);
    join(TARGET, "repair-1", "short").statusCode(400);
    assertEquals(0, events.size());
  }

  @Test
  @TestSecurity(user = TOKEN_SUBJECT, roles = "qits:target-runner-registration")
  void nothingJoinsBeforeAPrivateEdgeAnnouncedItself() {
    fixtures.clear();
    join(TARGET, "host-1", Fixtures.key(5))
        .statusCode(409)
        .body("message", equalTo("no private edge has announced itself"));
    assertNull(QuarkusTransaction.requiringNew().call(() -> NaNode.findById(TARGET)));
    assertEquals(0, events.size());
  }

  @Test
  @TestSecurity(user = TOKEN_SUBJECT, roles = "qits:target-runner-registration")
  void theRegistrationTokenDownloadsTheHelperAndReadsNothingElse() {
    byte[] binary =
        given()
            .get(NODES + "/qits-node-tunnel")
            .then()
            .statusCode(200)
            .contentType("application/octet-stream")
            .extract()
            .asByteArray();
    assertFalse(binary.length == 0);
    given().get(NODES).then().statusCode(403);
    given().get("/network-access/api/peers").then().statusCode(403);
  }
}
