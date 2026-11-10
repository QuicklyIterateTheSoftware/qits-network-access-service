package eu.wohlben.qits.networkaccess.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;

import eu.wohlben.qits.networkaccess.support.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The design page's role matrix, door by door: no door answers an anonymous caller (401), every
 * read admits {@code qits:agent}, the two writes refuse it (403) and admit each role of the write
 * set. Each test names exactly one role, so the identity holds that role and nothing else.
 */
@QuarkusTest
class RoleMatrixTest {

  static final String API = "/network-access/api";

  static final UUID PEER = UUID.fromString("3a4b5c6d-7e8f-4a0b-9c1d-2e3f4a5b6c7d");

  static final UUID TARGET = UUID.fromString("8b9c0d1e-2f3a-4b4c-8d5e-6f7a8b9c0d1e");

  /** Every read door, as method and path. */
  static final List<String[]> READS =
      List.of(
          new String[] {"GET", API + "/peers"},
          new String[] {"GET", API + "/peers/" + PEER},
          new String[] {"GET", API + "/peers/" + PEER + "/config"},
          new String[] {"GET", API + "/peers/" + PEER + "/tunnel"},
          new String[] {"GET", API + "/nodes"},
          new String[] {"GET", API + "/nodes/" + TARGET},
          new String[] {"GET", API + "/companion"},
          new String[] {"GET", API + "/private-edge"});

  @Inject Fixtures fixtures;

  @BeforeEach
  void arrange() {
    fixtures.clear();
    Fixtures.recordEvents();
    fixtures.edge();
    fixtures.device(PEER, "laptop", Fixtures.key(2), "100.96.0.10", "someone");
    fixtures.node(TARGET, "node-a", UUID.randomUUID(), Fixtures.key(3), "100.96.0.11");
  }

  @Test
  void noDoorAnswersAnAnonymousCaller() {
    for (String[] door : READS) {
      given().request(door[0], door[1]).then().statusCode(401);
    }
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "x", "publicKey", Fixtures.key(9)))
        .post(API + "/peers")
        .then()
        .statusCode(401);
    given().post(API + "/peers/" + PEER + "/revoke").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "an-agent", roles = "qits:agent")
  void theAgentReadsEveryDoor() {
    for (String[] door : READS) {
      given().request(door[0], door[1]).then().statusCode(200);
    }
  }

  @Test
  @TestSecurity(user = "an-agent", roles = "qits:agent")
  void theAgentIsRefusedBothWrites() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "x", "publicKey", Fixtures.key(9)))
        .post(API + "/peers")
        .then()
        .statusCode(403);
    given().post(API + "/peers/" + PEER + "/revoke").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "a-stranger", roles = "qits:something-else")
  void aRoleOutsideBothSetsIsRefusedEverywhere() {
    for (String[] door : READS) {
      given().request(door[0], door[1]).then().statusCode(403);
    }
  }

  @Test
  @TestSecurity(user = "the-admin", roles = "qits:admin")
  void adminWrites() {
    writes();
  }

  @Test
  @TestSecurity(user = "the-admin-agent", roles = "qits:admin-agent")
  void adminAgentWrites() {
    writes();
  }

  @Test
  @TestSecurity(user = "the-desk-admin", roles = "qits:desk-admin")
  void deskAdminWrites() {
    writes();
  }

  @Test
  @TestSecurity(user = "the-desk", roles = "qits:desk")
  void deskWrites() {
    writes();
  }

  @Test
  @TestSecurity(user = "dev-qits-system", roles = "qits:system")
  void systemWrites() {
    writes();
  }

  private void writes() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "x", "publicKey", Fixtures.key(9)))
        .post(API + "/peers")
        .then()
        .statusCode(201);
    given().post(API + "/peers/" + PEER + "/revoke").then().statusCode(200);
    // Reads stay open to the write set too.
    given().get(API + "/peers").then().statusCode(not(oneOf(401, 403)));
  }
}
