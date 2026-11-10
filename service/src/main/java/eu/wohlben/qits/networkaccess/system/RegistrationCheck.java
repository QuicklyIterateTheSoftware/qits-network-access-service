package eu.wohlben.qits.networkaccess.system;

import eu.wohlben.qits.networkaccess.control.NetworkAccessException;
import eu.wohlben.qits.networkaccess.system.SystemTargets.RegistrationAnswer;
import eu.wohlben.qits.networkaccess.system.SystemTargets.RegistrationQuestion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Whether a registration token is the given target's: qits-system's {@code
 * checkTargetRegistration}, asked with this service's own bearer. A node's join is admitted only on
 * its 200, and only when the answer names the target the join is for.
 *
 * <ul>
 *   <li>Any other answer — 403 (not this target's token), 404 (no such target), 401 (this
 *       service's own bearer refused) — is 403 {@value #REFUSED}. The caller learns nothing more:
 *       the reason is logged here.
 *   <li>qits-system unreachable is 503: nothing was decided, so the helper may simply retry.
 * </ul>
 */
@ApplicationScoped
public class RegistrationCheck {

  public static final String REFUSED = "not this target's registration token";

  private static final Logger LOG = Logger.getLogger(RegistrationCheck.class);

  @Inject @RestClient SystemTargets system;

  @Inject SystemBearer bearer;

  /**
   * The check over a given client and bearer, outside CDI: the consumer pact drives the real class
   * through a client built against its mock server.
   */
  public static RegistrationCheck over(SystemTargets system, SystemBearer bearer) {
    RegistrationCheck check = new RegistrationCheck();
    check.system = system;
    check.bearer = bearer;
    return check;
  }

  /**
   * The target's name at qits-system, when {@code subject} is its live registration token's.
   *
   * @throws NetworkAccessException 403 when it is not, 503 when qits-system cannot be asked
   */
  public String targetName(UUID targetId, String subject) {
    String authorization;
    try {
      authorization = bearer.authorization().orElse(null);
    } catch (RuntimeException noToken) {
      LOG.warnf("no qits bearer to ask qits-system with: %s", noToken.getMessage());
      throw NetworkAccessException.unavailable("qits-system cannot be asked; retry the join");
    }
    RegistrationAnswer answer;
    try {
      answer =
          system.checkTargetRegistration(
              authorization, targetId, new RegistrationQuestion(subject));
    } catch (WebApplicationException refused) {
      LOG.infof(
          "qits-system refused %s's registration for target %s: %d",
          subject, targetId, refused.getResponse().getStatus());
      throw NetworkAccessException.forbidden(REFUSED);
    } catch (ProcessingException unreachable) {
      LOG.warnf(
          "qits-system could not be asked about target %s's registration: %s",
          targetId, unreachable.getMessage());
      throw NetworkAccessException.unavailable("qits-system cannot be reached; retry the join");
    }
    if (answer == null || !targetId.toString().equals(answer.targetId())) {
      LOG.warnf(
          "qits-system answered target %s's registration check for %s",
          targetId, answer == null ? null : answer.targetId());
      throw NetworkAccessException.forbidden(REFUSED);
    }
    return answer.targetName();
  }
}
