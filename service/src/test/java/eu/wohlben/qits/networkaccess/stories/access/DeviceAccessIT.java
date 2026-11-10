package eu.wohlben.qits.networkaccess.stories.access;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.networkaccess.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The service as it is <b>packaged</b>, with the OIDC tenant <b>on</b> against {@link MockIdp} —
 * the shipped validation block (discovery, JWKS, audience {@code qits-platform}, {@code groups} as
 * roles) that no {@code @QuarkusTest} here exercises, since every suite turns the gate off and
 * states its caller with {@code @TestSecurity}.
 *
 * <p>It is also this repository's <b>userflow catalogue</b>: the stories' documentation, with an
 * observed network diagram, is written under {@code service/target/userstories/}, which the
 * java-service release recipe publishes as {@code @userflows/qits-network-access}. Both stories are
 * browserless. They are ordered, because the startup JWKS fetch is attributed to whichever story
 * drains first, and that has to be the first one.
 *
 * <p>Run with {@code ./mvnw verify -DskipITs=false}.
 */
@QuarkusIntegrationTest
@TestProfile(DeviceAccessIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DeviceAccessIT {

  static final String CATEGORY = "access";

  static final String ADD_SLUG = "a-person-adds-a-device-and-is-given-its-tunnel-address";

  static final String REFUSED_SLUG = "an-agent-reads-the-peers-but-never-adds-one";

  static final String SERVICE = "qits-network-access";

  static final String PEERS = "/network-access/api/peers";

  /** A person's {@code sub}: the idp user id, which becomes the device's owner. */
  static final String PERSON = "7c0d9e8a-1b2c-4d3e-8f4a-5b6c7d8e9f00";

  private static final List<String> MINTED = new ArrayList<>();

  /**
   * The packaged process's environment: both {@code QITS_RESOURCE_*} triples on this JVM's embedded
   * postgres (variables, so the shipped {@code ${QITS_RESOURCE_DB_URL}} expressions are the ones
   * under test), the gate on, and the idp moved to the mock. Every key is a RUNTIME key: a
   * packaged artifact takes its configuration as {@code -D} arguments. A launched artifact runs
   * under no %test profile, so telemetry and the bus are turned dark here too.
   */
  public static class PackagedWithMockIdp implements QuarkusTestProfile {

    private static final String URL_PROPERTY = "qits.test.userflow-it.db-url";

    private static final String EVENTSTREAM_URL_PROPERTY =
        "qits.test.userflow-it.eventstream-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> overrides = new LinkedHashMap<>();
      overrides.put("QITS_RESOURCE_DB_URL", databaseUrl(URL_PROPERTY, "network_access_userflow_it"));
      overrides.put("QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER);
      overrides.put("QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);
      overrides.put(
          "QITS_RESOURCE_EVENTSTREAM_URL",
          databaseUrl(EVENTSTREAM_URL_PROPERTY, "network_access_userflow_eventstream_it"));
      overrides.put("QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER);
      overrides.put("QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD);
      overrides.put("qits.auth.machine.required", "true");
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());
      overrides.put("quarkus.otel.sdk.disabled", "true");
      overrides.put("qits.eventstream.enabled", "false");
      return overrides;
    }

    /** One url per database per JVM, parked in a system property for the other classloaders. */
    static synchronized String databaseUrl(String property, String database) {
      String recorded = System.getProperty(property);
      if (recorded != null) {
        return recorded;
      }
      String url = EmbeddedPg.url(database);
      System.setProperty(property, url);
      return url;
    }
  }

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    NetworkTaps.restAssured(SERVICE);
    NetworkCapture.source(
        "mock-idp",
        () ->
            MockIdp.attach().recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
  }

  private static String mint(String subject, String role) {
    String token =
        MockIdp.attach()
            .token()
            .subject(subject)
            .audience("qits-platform")
            .groups(role)
            .mint();
    MINTED.add(token);
    return token;
  }

  /** A valid 44-character WireGuard public key; the device generated it, the service never sees the private half. */
  private static String key(int n) {
    byte[] bytes = new byte[32];
    ByteBuffer.wrap(bytes).putInt(n);
    bytes[31] = 7;
    return Base64.getEncoder().encodeToString(bytes);
  }

  @UserStory(value = "A person adds a device and is given its tunnel address", category = CATEGORY)
  @UserStoryDescription(
      """
      qits network-access up generates a WireGuard key where it is used, and only the public half
      leaves the machine: it registers the device here as a peer the person owns, and is given the
      lowest free address in the tunnel network, from 100.96.0.10 up. The private edge learns of
      the peer from the PeerRegistered event and adds it live. The service validated the person's
      bearer on the platform's own keys, which it fetched from qits-idp when it started.
      """)
  @Order(1)
  void aPersonAddsADevice(Interactions story) {
    MockIdp idp = MockIdp.attach();
    story.note("qits-network-access starts with the OIDC tenant on, beside a reachable qits-idp");
    given().get("/network-access/q/health/ready").then().statusCode(200);
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story.note("the signing keys were fetched at startup").as("jwks-fetched");

    NetworkCapture.actor("a person");
    String bearer = mint(PERSON, "qits:admin");
    given()
        .header("Authorization", "Bearer " + bearer)
        .contentType(ContentType.JSON)
        .body(Map.of("name", "laptop", "publicKey", key(2)))
        .post(PEERS)
        .then()
        .statusCode(201)
        .body("tunnelIp", equalTo("100.96.0.10"))
        .body("ownerUserId", equalTo(PERSON));
    story.note("the device is registered at 100.96.0.10, owned by the person's sub").as("added");

    given()
        .header("Authorization", "Bearer " + bearer)
        .queryParam("owner", "me")
        .get(PEERS)
        .then()
        .statusCode(200)
        .body("$", hasSize(1));
    story.note("owner=me lists exactly that device").as("listed");
  }

  @UserStory(value = "An agent reads the peers but never adds one", category = CATEGORY)
  @UserStoryDescription(
      """
      An agent may see who can reach the private edge, but granting access is a person's or the
      desk's: qits:agent reads every door and is refused 403 on the writes. A caller with no bearer
      at all is refused 401 everywhere — there is no anonymous door, although the deployment is
      public on purpose so that it survives a fault of the private edge it grants access to.
      """)
  @Order(2)
  void anAgentIsRefusedTheWrite(Interactions story) {
    NetworkCapture.actor("an agent");
    String bearer = mint("dev-qits-agent", "qits:agent");
    given().header("Authorization", "Bearer " + bearer).get(PEERS).then().statusCode(200);
    story.note("the agent reads the peers").as("agent-reads");
    given()
        .header("Authorization", "Bearer " + bearer)
        .contentType(ContentType.JSON)
        .body(Map.of("name", "sneaky", "publicKey", key(3)))
        .post(PEERS)
        .then()
        .statusCode(403);
    story.note("the agent is refused 403 adding a device").as("agent-refused");

    NetworkCapture.actor("an anonymous caller");
    given().get(PEERS).then().statusCode(401);
    story.note("a caller with no bearer is refused 401").as("anonymous-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    ReportAssertions.assertComplete(CATEGORY, ADD_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY, ADD_SLUG, "http", SERVICE, MockIdp.SERVICE_NAME, "GET /idp/jwks -> 200");
    ReportAssertions.assertEdge(
        CATEGORY, ADD_SLUG, "http", "a person", SERVICE, "POST " + PEERS + " -> 201");
    ReportAssertions.assertStepId(CATEGORY, ADD_SLUG, "added");
    ReportAssertions.assertComplete(CATEGORY, REFUSED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY, REFUSED_SLUG, "http", "an agent", SERVICE, "POST " + PEERS + " -> 403");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "anonymous-refused");
    for (String bearer : MINTED) {
      ReportAssertions.assertNotLeaked(CATEGORY, ADD_SLUG, bearer);
      ReportAssertions.assertNotLeaked(CATEGORY, REFUSED_SLUG, bearer);
    }
  }
}
