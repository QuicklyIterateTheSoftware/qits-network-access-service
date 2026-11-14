package eu.wohlben.qits.networkaccess.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.networkaccess.events.PeerRegistered;
import eu.wohlben.qits.networkaccess.events.PeerRevoked;
import eu.wohlben.qits.networkaccess.support.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The peer doors end to end against the real store: registration, the allocation (lowest free,
 * reuse after revoke, exhaustion at 245), revocation, both configs and the tunnel answer, and
 * exactly one event per write with the design table's fields (a fake bus, {@link
 * Fixtures#recordEvents()}).
 */
@QuarkusTest
@TestSecurity(user = PeersApiTest.PERSON, roles = "qits:admin")
class PeersApiTest {

  /** A person's sub: the idp user id. */
  static final String PERSON = "7c0d9e8a-1b2c-4d3e-8f4a-5b6c7d8e9f00";

  static final String PEERS = "/network-access/api/peers";

  @Inject Fixtures fixtures;

  List<QitsEvent> events;

  @BeforeEach
  void clean() {
    fixtures.clear();
    events = Fixtures.recordEvents();
  }

  private String create(String name, String key) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "publicKey", key))
        .post(PEERS)
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  @Test
  void aDeviceIsRegisteredAtTheLowestFreeAddressOwnedByTheCaller() {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "laptop", "publicKey", Fixtures.key(2)))
            .post(PEERS)
            .then()
            .statusCode(201)
            .body("kind", equalTo("DEVICE"))
            .body("name", equalTo("laptop"))
            .body("publicKey", equalTo(Fixtures.key(2)))
            .body("tunnelIp", equalTo("100.96.0.10"))
            .body("ownerUserId", equalTo(PERSON))
            .body("targetId", nullValue())
            .body("status", equalTo("ACTIVE"))
            .body("createdBy", equalTo(PERSON))
            .extract()
            .path("id");

    List<PeerRegistered> registered = Fixtures.of(events, PeerRegistered.class);
    assertEquals(1, events.size(), "exactly one event per write: " + events);
    PeerRegistered event = registered.getFirst();
    assertEquals(UUID.fromString(id), event.peerId());
    assertEquals("DEVICE", event.kind());
    assertEquals("laptop", event.peerName());
    assertEquals(Fixtures.key(2), event.publicKey());
    assertEquals("100.96.0.10", event.tunnelIp());
    assertEquals(PERSON, event.ownerUserId());
    assertNull(event.targetId());

    given().get(PEERS + "/" + id).then().statusCode(200).body("tunnelIp", equalTo("100.96.0.10"));
    assertEquals("100.96.0.11", given().get(PEERS + "/" + create("phone", Fixtures.key(3))).path("tunnelIp"));
  }

  @Test
  void aRevokedAddressIsTheNextOneGivenOut() {
    create("one", Fixtures.key(2));
    String second = create("two", Fixtures.key(3));
    create("three", Fixtures.key(4));
    given().post(PEERS + "/" + second + "/revoke").then().statusCode(200);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "four", "publicKey", Fixtures.key(5)))
        .post(PEERS)
        .then()
        .statusCode(201)
        .body("tunnelIp", equalTo("100.96.0.11"));
  }

  @Test
  void theReservedAddressesBelowTenAreNeverGivenOut() {
    fixtures.device(UUID.randomUUID(), "proof", Fixtures.key(9), "100.96.0.2", "someone");
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "laptop", "publicKey", Fixtures.key(2)))
        .post(PEERS)
        .then()
        .statusCode(201)
        .body("tunnelIp", equalTo("100.96.0.10"));
  }

  @Test
  void theTwoHundredFortySixthPeerFindsThePoolExhausted() {
    fixtures.fillPool(244, "someone");
    create("last", Fixtures.key(2));
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "one-too-many", "publicKey", Fixtures.key(3)))
        .post(PEERS)
        .then()
        .statusCode(409)
        .body("message", equalTo("the tunnel address pool is exhausted"));
    assertEquals(1, events.size(), "the refused registration published nothing: " + events);
  }

  @Test
  void aDuplicateKeyOrNameIs409AndABadOneIs400() {
    create("laptop", Fixtures.key(2));
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "other", "publicKey", Fixtures.key(2)))
        .post(PEERS)
        .then()
        .statusCode(409);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "laptop", "publicKey", Fixtures.key(3)))
        .post(PEERS)
        .then()
        .statusCode(409);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "Not A Label", "publicKey", Fixtures.key(3)))
        .post(PEERS)
        .then()
        .statusCode(400);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "ok", "publicKey", "not-a-key"))
        .post(PEERS)
        .then()
        .statusCode(400);
    assertEquals(1, events.size(), "only the first registration published: " + events);
  }

  @Test
  void anotherOwnersNameIsFree() {
    fixtures.device(UUID.randomUUID(), "laptop", Fixtures.key(9), "100.96.0.10", "someone-else");
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "laptop", "publicKey", Fixtures.key(2)))
        .post(PEERS)
        .then()
        .statusCode(201)
        .body("tunnelIp", equalTo("100.96.0.11"));
  }

  @Test
  void revokingPublishesOnceAndIsIdempotent() {
    String id = create("laptop", Fixtures.key(2));
    events.clear();

    given()
        .post(PEERS + "/" + id + "/revoke")
        .then()
        .statusCode(200)
        .body("status", equalTo("REVOKED"))
        .body("revokeReason", equalTo("REVOKED"))
        .body("revokedBy", equalTo(PERSON));
    String revokedAt = given().get(PEERS + "/" + id).path("revokedAt");

    assertEquals(1, events.size(), "exactly one event per write: " + events);
    PeerRevoked event = Fixtures.of(events, PeerRevoked.class).getFirst();
    assertEquals(UUID.fromString(id), event.peerId());
    assertEquals("DEVICE", event.kind());
    assertEquals("laptop", event.peerName());
    assertEquals(Fixtures.key(2), event.publicKey());
    assertEquals("100.96.0.10", event.tunnelIp());
    assertEquals("REVOKED", event.reason());
    assertEquals(Instant.parse(revokedAt), event.occurredAt());

    given()
        .post(PEERS + "/" + id + "/revoke")
        .then()
        .statusCode(200)
        .body("status", equalTo("REVOKED"))
        .body("revokedAt", equalTo(revokedAt));
    assertEquals(1, events.size(), "a second revoke publishes nothing: " + events);

    given().post(PEERS + "/" + UUID.randomUUID() + "/revoke").then().statusCode(404);
  }

  @Test
  void theListIsNewestFirstAndFilters() {
    fixtures.node(UUID.randomUUID(), "node-a", UUID.randomUUID(), Fixtures.key(9), "100.96.0.20");
    String first = create("first", Fixtures.key(2));
    String second = create("second", Fixtures.key(3));
    given().post(PEERS + "/" + first + "/revoke").then().statusCode(200);

    given().get(PEERS).then().statusCode(200).body("name", contains("second", "first", "node-a"));
    given().queryParam("kind", "DEVICE").get(PEERS).then().body("id", contains(second, first));
    given().queryParam("status", "ACTIVE").get(PEERS).then().body("name", contains("second", "node-a"));
    given().queryParam("owner", "me").get(PEERS).then().body("id", contains(second, first));
    given().queryParam("owner", "nobody").get(PEERS).then().body("$", hasSize(0));
    given().queryParam("kind", "TOASTER").get(PEERS).then().statusCode(400);
  }

  @Test
  void theConfigAndTheTunnelWaitForAnAnnouncedEdge() {
    String id = create("laptop", Fixtures.key(2));
    given()
        .get(PEERS + "/" + id + "/config")
        .then()
        .statusCode(409)
        .body("message", equalTo("no private edge has announced itself"));
    given().get(PEERS + "/" + id + "/tunnel").then().statusCode(409);

    fixtures.edge();
    String config =
        given()
            .get(PEERS + "/" + id + "/config")
            .then()
            .statusCode(200)
            .contentType(ContentType.TEXT)
            .extract()
            .asString();
    assertEquals(
        "# qits network access: laptop (" + id + "), DEVICE\n"
            + "# Linux: save as /etc/wireguard/qits.conf, then `wg-quick up qits`. Needs"
            + " systemd-resolved for split DNS.\n"
            + "[Interface]\n"
            + "PrivateKey = <PRIVATE_KEY>\n"
            + "Address = 100.96.0.10/32\n"
            + "MTU = 1380\n"
            + "PostUp = resolvectl dns %i 100.96.0.1; resolvectl domain %i ~wohlben.eu\n"
            + "PreDown = resolvectl revert %i\n"
            + "\n"
            + "[Peer]\n"
            + "PublicKey = "
            + Fixtures.EDGE_PUBLIC_KEY
            + "\n"
            + "Endpoint = 46.224.171.33:47812\n"
            + "AllowedIPs = 100.96.0.1/32\n"
            + "PersistentKeepalive = 25\n",
        config);

    given()
        .get(PEERS + "/" + id + "/tunnel")
        .then()
        .statusCode(200)
        .body("peerId", equalTo(id))
        .body("tunnelIp", equalTo("100.96.0.10"))
        .body("edge.publicKey", equalTo(Fixtures.EDGE_PUBLIC_KEY))
        .body("edge.publicAddress", equalTo("46.224.171.33"))
        .body("edge.listenPort", equalTo(47812))
        .body("edge.dnsAddress", equalTo("100.96.0.1"))
        .body("edge.tunnelCidr", equalTo("100.96.0.0/24"))
        .body("edge.mtu", equalTo(1380))
        .body("edge.domain", equalTo("wohlben.eu"));
  }

  @Test
  void theAppConfigWaitsForAnAnnouncedEdgeAndSetsTheTunnelResolver() {
    String id = create("phone", Fixtures.key(2));
    given()
        .get(PEERS + "/" + id + "/app-config")
        .then()
        .statusCode(409)
        .body("message", equalTo("no private edge has announced itself"));

    fixtures.edge();
    String config =
        given()
            .get(PEERS + "/" + id + "/app-config")
            .then()
            .statusCode(200)
            .contentType(ContentType.TEXT)
            .extract()
            .asString();
    assertEquals(
        "# qits network access: phone (" + id + "), DEVICE\n"
            + "[Interface]\n"
            + "PrivateKey = <PRIVATE_KEY>\n"
            + "Address = 100.96.0.10/32\n"
            + "DNS = 100.96.0.1\n"
            + "MTU = 1380\n"
            + "\n"
            + "[Peer]\n"
            + "PublicKey = "
            + Fixtures.EDGE_PUBLIC_KEY
            + "\n"
            + "Endpoint = 46.224.171.33:47812\n"
            + "AllowedIPs = 100.96.0.1/32\n"
            + "PersistentKeepalive = 25\n",
        config);

    given().get(PEERS + "/" + UUID.randomUUID() + "/app-config").then().statusCode(404);
  }

  @Test
  void thePrivateEdgeIs404UntilAnnouncedAndTheCompanionNamesThisVersion() {
    given().get("/network-access/api/private-edge").then().statusCode(404);
    fixtures.edge();
    given()
        .get("/network-access/api/private-edge")
        .then()
        .statusCode(200)
        .body("application", equalTo(Fixtures.EDGE_APPLICATION))
        .body("listenPort", equalTo(47812));
    given()
        .get("/network-access/api/companion")
        .then()
        .statusCode(200)
        .body("image", equalTo("registry.qits.wohlben.eu/qits/qits-network-access"))
        .body("version", equalTo("2026.1110.120000"))
        .body("entrypoint", equalTo("/work/companion/qits-access-companion"))
        .body("user", equalTo("65532:65532"));
  }

  @Test
  void nodesAnswerWithTheirActivePeer() {
    UUID target = UUID.randomUUID();
    UUID peer = UUID.randomUUID();
    fixtures.node(target, "node-a", peer, Fixtures.key(9), "100.96.0.20");
    given()
        .get("/network-access/api/nodes")
        .then()
        .statusCode(200)
        .body("targetId", contains(target.toString()))
        .body("[0].peer.id", equalTo(peer.toString()))
        .body("[0].peer.kind", equalTo("NODE"));
    given()
        .get("/network-access/api/nodes/" + target)
        .then()
        .statusCode(200)
        .body("peer.tunnelIp", equalTo("100.96.0.20"));
    given().get("/network-access/api/nodes/" + UUID.randomUUID()).then().statusCode(404);
  }
}
