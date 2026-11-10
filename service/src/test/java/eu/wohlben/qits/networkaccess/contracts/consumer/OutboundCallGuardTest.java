package eu.wohlben.qits.networkaccess.contracts.consumer;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import eu.wohlben.qits.networkaccess.system.SystemBearer;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.junit.jupiter.api.Test;

/**
 * <b>Every call this service makes to a qits service has a pact row</b> (qits-1149), the calls read
 * off the code rather than listed by hand:
 *
 * <ul>
 *   <li>no production class reaches a way to dial out — {@code java.net.http.HttpClient}, {@code
 *       HttpURLConnection}, a Vert.x {@code WebClient} or {@code HttpClient}, or {@code
 *       quarkus-oidc-client}'s {@code OidcClient} — except {@link SystemBearer}, which asks the
 *       named client {@code qits} for this service's own bearer;
 *   <li>every MicroProfile REST client ({@code @RegisterRestClient}) names a provider of {@link
 *       #PROVIDERS}, and each of its methods is a row of that provider's pact whose recorded method
 *       and route are the method's;
 *   <li>the named client {@code qits} is the only client-credentials mint, and its mint is rowed;
 *       the tenant's discovery and JWKS reads are rowed.
 * </ul>
 *
 * <p>Not seen here, because a library makes them: the eventstream publisher's and catch-up reader's
 * calls of qits-events ({@code EventstreamPactTest}) and the OTLP export.
 */
class OutboundCallGuardTest {

  private static final Set<String> DIAL_OUT =
      Set.of(
          "java.net.http.HttpClient",
          "java.net.HttpURLConnection",
          "io.vertx.ext.web.client.WebClient",
          "io.vertx.core.http.HttpClient",
          "io.quarkus.oidc.client.OidcClient",
          "io.quarkus.oidc.client.OidcClients");

  /** The REST clients' {@code configKey}s, each with the pact that rows its calls. */
  private static final Map<String, ConsumerPactRows> PROVIDERS =
      Map.of(
          "qits-system",
          new ConsumerPactRows(SystemPactTest.SYSTEM, SystemPactTest.PACT.rows()));

  private record ConsumerPactRows(GoldenMasters masters, List<GoldenInteraction> rows) {}

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("eu.wohlben.qits.networkaccess");

  @Test
  void noProductionClassDialsAnotherServiceOutsideTheNamedOnes() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(SystemBearer.class.getName())
        .should()
        .dependOnClassesThat()
        .haveNameMatching(
            DIAL_OUT.stream().map(name -> name.replace(".", "\\.")).collect(Collectors.joining("|")))
        .because(
            "an outbound call to another qits service goes through a REST client whose every"
                + " method has a pact row against that provider's golden masters (qits-1149)")
        .check(PRODUCTION);
  }

  @Test
  void everyRestClientMethodIsARecordedRow() throws Exception {
    List<String> unrowed = new ArrayList<>();
    int methods = 0;
    for (JavaClass type : PRODUCTION) {
      if (!type.isAnnotatedWith(RegisterRestClient.class)) {
        continue;
      }
      Class<?> client = Class.forName(type.getName());
      String key = client.getAnnotation(RegisterRestClient.class).configKey();
      ConsumerPactRows provider = PROVIDERS.get(key);
      assertTrue(provider != null, client.getName() + " calls `" + key + "`, which has no pact here");
      String base = client.isAnnotationPresent(Path.class) ? client.getAnnotation(Path.class).value() : "";
      for (Method method : client.getDeclaredMethods()) {
        String verb = verb(method);
        if (verb == null) {
          continue;
        }
        methods++;
        String route =
            normalise(base + (method.isAnnotationPresent(Path.class) ? method.getAnnotation(Path.class).value() : ""));
        boolean rowed =
            provider.rows().stream()
                .filter(row -> provider.masters().recorded(row.state(), row.operationId()))
                .map(row -> provider.masters().operation(row.state(), row.operationId()))
                .anyMatch(op -> op.method().equals(verb) && normalise(op.path()).equals(route));
        if (!rowed) {
          unrowed.add(client.getSimpleName() + "." + method.getName() + " " + verb + " " + route);
        }
      }
    }
    assertTrue(methods > 0, "the sweep found no REST client method at all");
    assertEquals(List.of(), unrowed, "REST client calls without a recorded pact row");
  }

  @Test
  void theConfiguredOidcTenantsReadsArePacted() throws IOException {
    Properties properties = shipped();
    String server = properties.getProperty("quarkus.oidc.auth-server-url", "");
    assertTrue(server.contains("//idp.qits."), "the tenant reads qits-idp at its edge host: " + server);
    Set<String> rowed =
        IdpPactTest.PACT.rows().stream().map(GoldenInteraction::operationId).collect(Collectors.toSet());
    assertTrue(rowed.contains("getJwks"), "the tenant reads the JWKS: row getJwks");
    boolean discovery =
        Boolean.parseBoolean(properties.getProperty("quarkus.oidc.discovery-enabled", "true"));
    assertEquals(discovery, rowed.contains("getOpenIdConfiguration"), "the discovery row follows the tenant");
  }

  @Test
  void theQitsClientIsTheOnlyMintAndItIsRowed() throws IOException {
    Properties properties = shipped();
    Set<String> clientKeys =
        properties.stringPropertyNames().stream()
            .filter(name -> name.startsWith("quarkus.oidc-client."))
            .collect(Collectors.toCollection(TreeSet::new));
    assertFalse(clientKeys.isEmpty(), "the qits client is configured in application.properties");
    assertEquals(
        Set.of(),
        clientKeys.stream()
            .filter(name -> !name.startsWith("quarkus.oidc-client.qits."))
            .collect(Collectors.toSet()),
        "only the named client `qits` mints tokens here");
    assertEquals("client", properties.getProperty("quarkus.oidc-client.qits.grant.type"));
    assertTrue(
        IdpPactTest.PACT.rows().stream().anyMatch(row -> row.operationId().equals("issueToken")),
        "the qits client mints at qits-idp: row issueToken in IdpPactTest");
  }

  /** The shipped file, not the classpath's: a test resource of the same name shadows it there. */
  private static Properties shipped() throws IOException {
    Properties properties = new Properties();
    try (InputStream in =
        Files.newInputStream(java.nio.file.Path.of("src/main/resources/application.properties"))) {
      properties.load(in);
    }
    return properties;
  }

  private static String verb(Method method) {
    for (Annotation annotation : method.getAnnotations()) {
      HttpMethod http = annotation.annotationType().getAnnotation(HttpMethod.class);
      if (http != null) {
        return http.value();
      }
    }
    return null;
  }

  /** A route with every {@code {param}} as {@code {}} and no doubled or trailing slash. */
  private static String normalise(String route) {
    String out = route.replaceAll("\\{[^}]*}", "{}").replaceAll("/+", "/");
    return out.length() > 1 && out.endsWith("/") ? out.substring(0, out.length() - 1) : out;
  }
}
