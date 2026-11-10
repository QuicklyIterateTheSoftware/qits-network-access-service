package eu.wohlben.qits.networkaccess.api;

import eu.wohlben.qits.networkaccess.api.NetworkAccessWire.CompanionDto;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * How the CLI runs the companion: this service's own image, {@code
 * registry.qits.<domain>/qits/qits-network-access}, at exactly this service's version, with the
 * entrypoint {@code /work/companion/qits-access-companion} and the user {@code 65532:65532}. The
 * companion rides inside the service image (one image per application is what the release platform
 * publishes), so the version a deployment answers is the one whose companion speaks its tunnel
 * answer.
 *
 * <p>The version is {@code quarkus.application.version}: the pom version, which a release stamps
 * with the released calver.
 */
@Path("/companion")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({
  Roles.ADMIN,
  Roles.ADMIN_AGENT,
  Roles.DESK_ADMIN,
  Roles.DESK,
  Roles.AGENT,
  Roles.SYSTEM
})
public class CompanionResource {

  @ConfigProperty(name = "qits.network-access.companion.image")
  String image;

  @ConfigProperty(name = "quarkus.application.version")
  String version;

  @ConfigProperty(name = "qits.network-access.companion.entrypoint")
  String entrypoint;

  @ConfigProperty(name = "qits.network-access.companion.user")
  String user;

  @GET
  @Operation(operationId = "getCompanion", summary = "How to run the companion: image, version, entrypoint, user")
  @APIResponse(responseCode = "200", description = "The image, its version, entrypoint and user")
  public CompanionDto get() {
    return new CompanionDto(image, version, entrypoint, user);
  }
}
