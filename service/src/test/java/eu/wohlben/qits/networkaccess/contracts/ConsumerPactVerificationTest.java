package eu.wohlben.qits.networkaccess.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against the running qits-network-access</b>, the way
 * qits-stt-service and qits-edge-service do.
 *
 * <p>Each consumer publishes its pact as a jar holding {@code
 * pacts/<consumer>_qits-network-access-service.json}; this repository pins the jar as a test
 * dependency and {@link ClasspathPactLoader} finds it. <b>No consumer pins one yet</b> — the
 * landing app's pane and the CLI's {@code network-access} commands arrive with their own tasks — so
 * this class carries {@link IgnoreNoPactsToVerify} and {@link ClasspathPactLoader#REQUIRED} is
 * false. When the first is pinned, drop the annotation and set {@code REQUIRED} to true.
 *
 * <p>Each interaction runs over real HTTP as an operator holding {@code qits:admin}. Every {@code
 * @State} method delegates to {@link ProviderStates}; {@link #target} fails an unknown state, and an
 * interaction without {@code comments.references.qits-call} or {@code qits-trigger}.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
@IgnoreNoPactsToVerify
@TestSecurity(user = ProviderStates.OPERATOR, roles = "qits:admin")
class ConsumerPactVerificationTest {

  /** The provider's name in a pact: the repository name, not the application name. */
  static final String PROVIDER = "qits-network-access-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    if (context == null) {
      return; // No pact: nothing to target.
    }
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-network-access does not answer for — it answers for "
                + states.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    if (context != null) {
      context.verifyInteraction();
    }
  }

  // --- the states: each one line into the registry -------------------------------------------

  @State(ProviderStates.AN_ANNOUNCED_PRIVATE_EDGE)
  Map<String, String> anAnnouncedPrivateEdge() {
    return states.params(ProviderStates.AN_ANNOUNCED_PRIVATE_EDGE);
  }

  @State(ProviderStates.A_DEVICE_PEER)
  Map<String, String> aDevicePeer() {
    return states.params(ProviderStates.A_DEVICE_PEER);
  }

  @State(ProviderStates.A_JOINED_NODE)
  Map<String, String> aJoinedNode() {
    return states.params(ProviderStates.A_JOINED_NODE);
  }
}
