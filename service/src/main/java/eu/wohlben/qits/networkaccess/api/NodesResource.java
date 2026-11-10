package eu.wohlben.qits.networkaccess.api;

import eu.wohlben.qits.networkaccess.api.NetworkAccessWire.EdgeDto;
import eu.wohlben.qits.networkaccess.api.NetworkAccessWire.JoinNodeRequest;
import eu.wohlben.qits.networkaccess.api.NetworkAccessWire.NodeDto;
import eu.wohlben.qits.networkaccess.api.NetworkAccessWire.TunnelDto;
import eu.wohlben.qits.networkaccess.control.NetworkAccessException;
import eu.wohlben.qits.networkaccess.control.PeerRegistry;
import eu.wohlben.qits.networkaccess.control.PeerValidation;
import eu.wohlben.qits.networkaccess.entity.NaNode;
import eu.wohlben.qits.networkaccess.entity.NaPeer;
import eu.wohlben.qits.networkaccess.entity.NaPrivateEdge;
import eu.wohlben.qits.networkaccess.system.RegistrationCheck;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;

/**
 * The nodes: the ones that joined the tunnel, each with its ACTIVE peer; the join itself; and the
 * helper binary a node's install line downloads to make it (section 5 of the design).
 *
 * <p><b>The join</b> admits two kinds of caller. A node's registration token ({@code
 * qits:target-runner-registration}) is checked against qits-system ({@link RegistrationCheck}):
 * only the token of THIS target may join it, and qits-system's target name is the node's name. The
 * write set skips the check — an operator's repair — and names the node by the body.
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

  private static final Logger LOG = Logger.getLogger(NodesResource.class);

  @Inject PeerRegistry registry;

  @Inject RegistrationCheck registrations;

  @Inject SecurityIdentity identity;

  /** Where the image keeps the static {@code qits-node-tunnel} built from {@code access-client/}. */
  @ConfigProperty(name = "qits.network-access.node-tunnel.path")
  String nodeTunnel;

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

  @POST
  @Path("/{targetId}/join")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({
    Roles.ADMIN,
    Roles.ADMIN_AGENT,
    Roles.DESK_ADMIN,
    Roles.DESK,
    Roles.SYSTEM,
    Roles.TARGET_RUNNER_REGISTRATION
  })
  @Operation(
      operationId = "joinNode",
      summary =
          "Join a qits-system target as a node: register its peer, or answer the one it has under"
              + " this key")
  @APIResponse(responseCode = "200", description = "The node's tunnel parameters")
  @APIResponse(responseCode = "400", description = "A bad name or public key")
  @APIResponse(
      responseCode = "403",
      description = "A registration token that is not this target's")
  @APIResponse(
      responseCode = "409",
      description =
          "No private edge has announced itself, a key another active peer holds, or a full pool")
  @APIResponse(responseCode = "503", description = "qits-system could not be asked")
  public TunnelDto join(@PathParam("targetId") UUID targetId, JoinNodeRequest request) {
    if (request == null) {
      throw NetworkAccessException.badRequest("a body {name, publicKey} is required");
    }
    String caller = Callers.subject(identity);
    String name = request.name();
    if (Roles.WRITE.stream().noneMatch(identity::hasRole)) {
      String targetName = registrations.targetName(targetId, caller);
      // qits-system's name for the target is the node's, when it is a DNS label (its own rule
      // allows a 64th character and a trailing dash, which a peer name does not).
      if (PeerValidation.isName(targetName)) {
        name = targetName;
      }
    } else {
      LOG.infof("%s joins the node %s without a registration check (write set)", caller, targetId);
    }
    // Refused before anything is written: a node joined behind no edge could not use the answer.
    NaPrivateEdge edge =
        registry.privateEdge().orElseThrow(() -> NetworkAccessException.conflict(PeersResource.NO_EDGE));
    NaPeer peer = registry.joinNode(targetId, name, request.publicKey(), caller);
    return new TunnelDto(peer.id, peer.tunnelIp, EdgeDto.of(edge));
  }

  @GET
  @Path("/qits-node-tunnel")
  @Produces(MediaType.APPLICATION_OCTET_STREAM)
  @RolesAllowed({
    Roles.ADMIN,
    Roles.ADMIN_AGENT,
    Roles.DESK_ADMIN,
    Roles.DESK,
    Roles.AGENT,
    Roles.SYSTEM,
    Roles.TARGET_RUNNER_REGISTRATION
  })
  @Operation(
      operationId = "getNodeTunnel",
      summary = "The static qits-node-tunnel helper a node's install line runs to join")
  @APIResponse(responseCode = "200", description = "The binary")
  @APIResponse(responseCode = "404", description = "This image carries no helper")
  public Response nodeTunnel() {
    File binary = new File(nodeTunnel);
    if (!binary.isFile()) {
      throw NetworkAccessException.notFound("this image carries no qits-node-tunnel");
    }
    return Response.ok(binary, MediaType.APPLICATION_OCTET_STREAM_TYPE)
        .header("Content-Disposition", "attachment; filename=\"qits-node-tunnel\"")
        .build();
  }

  private NodeDto dto(NaNode node) {
    return NodeDto.of(node, registry.activePeerOf(node.targetId).orElse(null));
  }
}
