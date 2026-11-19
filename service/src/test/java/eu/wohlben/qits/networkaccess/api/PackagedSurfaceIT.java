package eu.wohlben.qits.networkaccess.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import eu.wohlben.qits.networkaccess.stories.access.DeviceAccessIT;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The management page as the <em>packaged artifact</em> serves it. Quinoa is disabled by default in
 * test mode, so no {@code @QuarkusTest} builds or serves the page, and every assertion about {@code
 * /} would pass against a process with no page in it; this is the one place it is proved.
 *
 * <p>It runs under {@link DeviceAccessIT}'s profile on purpose: the same launch, the OIDC tenant on
 * against the mock idp, so "served without a bearer" is asserted against the shipped validation and
 * not against a process with the gate off.
 *
 * <p>It needs the frontend built in {@code service/src/main/webui} ({@code npm ci && npm run
 * build}), as every {@code ./mvnw verify -DskipITs=false} here does.
 *
 * <p><b>The release gate packages with {@code -Dquarkus.quinoa=false}</b> (qits-ci's java-service
 * recipe), and this repository keeps no {@code .config/qits/userflow-stories} allow-list, so the
 * pom runs every IT there too. Under that flag there is no page to serve: the two page probes are
 * skipped, never passed, and the two API probes run in every build. The image build packages with
 * Quinoa on and refuses to start without a built page ({@code docker/Dockerfile}).
 */
@QuarkusIntegrationTest
@TestProfile(DeviceAccessIT.PackagedWithMockIdp.class)
public class PackagedSurfaceIT {

  /**
   * The one string that identifies a response as the page's index.html. It also has to agree with
   * {@code quarkus.quinoa.ui-root-path} and with {@code baseHref} in qits-network-access-frontend's
   * angular.json, so the probes below double as the check that all three still do.
   */
  private static final String BASE_HREF = "<base href=\"/\">";

  /** Skips a page probe when the artifact was packaged without the page; see the class comment. */
  private static void assumeThePageIsPackaged() {
    Assumptions.assumeFalse(
        "false".equals(System.getProperty("quarkus.quinoa")),
        "packaged with -Dquarkus.quinoa=false: no page to probe");
  }

  /**
   * A browser navigation carries no bearer; the edge gates the host by session and the page then
   * obtains its bearer itself. So the page must be served to an anonymous request.
   */
  @Test
  public void thePageIsServedAtTheRootWithoutABearer() {
    assumeThePageIsPackaged();
    given()
        .accept(ContentType.HTML)
        .when()
        .get("/")
        .then()
        .statusCode(200)
        .contentType(ContentType.HTML)
        .body(containsString(BASE_HREF));
  }

  /**
   * The sign-in returns to {@code /auth/callback}, which has no file behind it: {@code
   * enable-spa-routing} is what makes it reach the Angular router instead of a 404.
   */
  @Test
  public void theSignInCallbackFallsBackToThePage() {
    assumeThePageIsPackaged();
    given()
        .accept(ContentType.HTML)
        .when()
        .get("/auth/callback")
        .then()
        .statusCode(200)
        .contentType(ContentType.HTML)
        .body(containsString(BASE_HREF));
  }

  /** Serving the page anonymously opened no door: the API still refuses a call with no bearer. */
  @Test
  public void theApiStillRefusesACallWithoutABearer() {
    given().when().get("/network-access/api/peers").then().statusCode(401);
  }

  /**
   * THE HALF THAT COSTS SOMETHING IF IT IS WRONG. The SPA fallback is a catch-all over the whole
   * root, so a machine path that matches no route would answer {@code 200 text/html} unless {@code
   * quarkus.quinoa.ignored-path-prefixes} claims it. Its one entry, {@code /network-access}, covers
   * both machine prefixes: matching is by path segment.
   */
  @Test
  public void aMistypedMachinePathIsA404NeverThePage() {
    given()
        .when()
        .get("/network-access/api/nope")
        .then()
        .statusCode(404)
        .body(not(containsString(BASE_HREF)));

    given()
        .when()
        .get("/network-access/q/nope")
        .then()
        .statusCode(404)
        .body(not(containsString(BASE_HREF)));
  }
}
