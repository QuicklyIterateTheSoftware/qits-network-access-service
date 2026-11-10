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
 * Which companion image the CLI runs: {@code registry.qits.<domain>/qits/qits-network-access-companion}
 * at exactly this service's version. The companion is built from this repository and released with
 * it, so the version a deployment answers is the one whose companion speaks its tunnel answer.
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

  @GET
  @Operation(operationId = "getCompanion", summary = "The companion image and version to run")
  @APIResponse(responseCode = "200", description = "The image and its version")
  public CompanionDto get() {
    return new CompanionDto(image, version);
  }
}
