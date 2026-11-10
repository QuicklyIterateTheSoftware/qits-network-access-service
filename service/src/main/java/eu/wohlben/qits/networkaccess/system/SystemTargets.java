package eu.wohlben.qits.networkaccess.system;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * qits-system's one door this service calls: {@code checkTargetRegistration}, {@code POST
 * /system/api/targets/{id}/registration-check} {@code {subject}}, which answers 200 {@code
 * {targetId, targetName}} when {@code subject} is the subject of that target's live registration
 * token, 403 when it is not and 404 for an unknown target. The caller must hold {@code
 * qits:system}: the bearer is this service's own {@code qits} oidc client's ({@link SystemBearer}).
 *
 * <p>The address is {@code qits.network-access.system.url} (scheme, host and port, no path);
 * qits-system serves its own {@code /system/api} segment. The pact is {@code
 * pacts/qits-network-access-service_qits-system-service.json} ({@code SystemPactTest}).
 */
@Path("/system/api")
@RegisterRestClient(configKey = "qits-system")
public interface SystemTargets {

  @POST
  @Path("/targets/{targetId}/registration-check")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  RegistrationAnswer checkTargetRegistration(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @PathParam("targetId") UUID targetId,
      RegistrationQuestion question);

  /** The body: the subject of the caller's registration token. */
  record RegistrationQuestion(String subject) {}

  /** The 200 answer, narrowed to what the join reads; anything else qits-system adds is ignored. */
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  record RegistrationAnswer(String targetId, String targetName) {}
}
