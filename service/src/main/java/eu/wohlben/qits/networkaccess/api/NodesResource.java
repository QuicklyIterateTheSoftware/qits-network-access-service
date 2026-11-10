package eu.wohlben.qits.networkaccess.api;

import eu.wohlben.qits.networkaccess.api.NetworkAccessWire.NodeDto;
import eu.wohlben.qits.networkaccess.control.PeerRegistry;
import eu.wohlben.qits.networkaccess.entity.NaNode;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The nodes that joined the tunnel, each with its ACTIVE peer. The join itself ({@code POST
 * /nodes/{targetId}/join}) and the helper binary ({@code GET /nodes/qits-node-tunnel}) arrive with
 * section 5 of the design.
 */
@Path("/nodes")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({
  Roles.ADMIN,
  Roles.ADMIN_AGENT,
  Roles.DESK_ADMIN,
  Roles.DESK,
  Roles.AGENT,
  Roles.SYSTEM
})
public class NodesResource {

  @Inject PeerRegistry registry;

  @GET
  @Operation(operationId = "getNodes", summary = "The joined nodes, with their active peers")
  @APIResponse(responseCode = "200", description = "The nodes, by name")
  public List<NodeDto> list() {
    return registry.nodes().stream().map(this::dto).toList();
  }

  @GET
  @Path("/{targetId}")
  @Operation(operationId = "getNode", summary = "One joined node, with its active peer")
  @APIResponse(responseCode = "200", description = "The node")
  @APIResponse(responseCode = "404", description = "No such node")
  public NodeDto get(@PathParam("targetId") UUID targetId) {
    return dto(registry.node(targetId));
  }

  private NodeDto dto(NaNode node) {
    return NodeDto.of(node, registry.activePeerOf(node.targetId).orElse(null));
  }
}
