package eu.wohlben.qits.networkaccess.api;

import eu.wohlben.qits.networkaccess.api.NetworkAccessWire.PrivateEdgeDto;
import eu.wohlben.qits.networkaccess.control.NetworkAccessException;
import eu.wohlben.qits.networkaccess.control.PeerRegistry;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/** The private edge as it last announced itself, or 404 until one has. */
@Path("/private-edge")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({
  Roles.ADMIN,
  Roles.ADMIN_AGENT,
  Roles.DESK_ADMIN,
  Roles.DESK,
  Roles.AGENT,
  Roles.SYSTEM
})
public class PrivateEdgeResource {

  @Inject PeerRegistry registry;

  @GET
  @Operation(operationId = "getPrivateEdge", summary = "The announced private edge")
  @APIResponse(responseCode = "200", description = "The edge")
  @APIResponse(responseCode = "404", description = "No private edge has announced itself")
  public PrivateEdgeDto get() {
    return registry
        .privateEdge()
        .map(PrivateEdgeDto::of)
        .orElseThrow(() -> NetworkAccessException.notFound(PeersResource.NO_EDGE));
  }
}
