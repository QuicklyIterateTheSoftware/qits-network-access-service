package eu.wohlben.qits.networkaccess.api;

import eu.wohlben.qits.networkaccess.api.NetworkAccessWire.CreatePeerRequest;
import eu.wohlben.qits.networkaccess.api.NetworkAccessWire.EdgeDto;
import eu.wohlben.qits.networkaccess.api.NetworkAccessWire.PeerDto;
import eu.wohlben.qits.networkaccess.api.NetworkAccessWire.TunnelDto;
import eu.wohlben.qits.networkaccess.control.NetworkAccessException;
import eu.wohlben.qits.networkaccess.control.PeerConfig;
import eu.wohlben.qits.networkaccess.control.PeerRegistry;
import eu.wohlben.qits.networkaccess.entity.NaPeer;
import eu.wohlben.qits.networkaccess.entity.NaPrivateEdge;
import eu.wohlben.qits.networkaccess.entity.PeerKind;
import eu.wohlben.qits.networkaccess.entity.PeerStatus;
import eu.wohlben.qits.networkaccess.entity.RevokeReason;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The peers: list and read them, add a device, revoke one, and hand out what a client needs to
 * connect — the {@code wg-quick} config, the WireGuard apps' config and the tunnel parameters. The
 * READ set at class level, the WRITE set on the two writes (see {@link Roles}).
 */
@Path("/peers")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({
  Roles.ADMIN,
  Roles.ADMIN_AGENT,
  Roles.DESK_ADMIN,
  Roles.DESK,
  Roles.AGENT,
  Roles.SYSTEM
})
public class PeersResource {

  /** The 409 a config or tunnel answers before any private edge announced itself. */
  static final String NO_EDGE = "no private edge has announced itself";

  @Inject PeerRegistry registry;

  @Inject SecurityIdentity identity;

  @GET
  @Operation(operationId = "getPeers", summary = "The peers, newest first")
  @APIResponse(responseCode = "200", description = "The peers matching every given filter")
  @APIResponse(responseCode = "400", description = "An unknown kind or status")
  public List<PeerDto> list(
      @QueryParam("kind") String kind,
      @QueryParam("status") String status,
      @QueryParam("owner") String owner) {
    String ownerFilter =
        owner == null || owner.isBlank()
            ? null
            : "me".equals(owner) ? Callers.subject(identity) : owner;
    return registry
        .list(parse(PeerKind.class, "kind", kind), parse(PeerStatus.class, "status", status), ownerFilter)
        .stream()
        .map(PeerDto::of)
        .toList();
  }

  @GET
  @Path("/{peerId}")
  @Operation(operationId = "getPeer", summary = "One peer")
  @APIResponse(responseCode = "200", description = "The peer")
  @APIResponse(responseCode = "404", description = "No such peer")
  public PeerDto get(@PathParam("peerId") UUID peerId) {
    return PeerDto.of(registry.get(peerId));
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.ADMIN, Roles.ADMIN_AGENT, Roles.DESK_ADMIN, Roles.DESK, Roles.SYSTEM})
  @Operation(
      operationId = "createPeer",
      summary = "Add a device peer owned by the caller, at the lowest free tunnel address")
  @APIResponse(responseCode = "201", description = "The new DEVICE peer")
  @APIResponse(responseCode = "400", description = "A bad name or public key")
  @APIResponse(
      responseCode = "409",
      description = "A duplicate public key, a duplicate name, or a full address pool")
  public Response create(CreatePeerRequest request) {
    if (request == null) {
      throw NetworkAccessException.badRequest("a body {name, publicKey} is required");
    }
    String caller = Callers.subject(identity);
    NaPeer peer = registry.registerDevice(request.name(), request.publicKey(), caller, caller);
    return Response.status(Response.Status.CREATED).entity(PeerDto.of(peer)).build();
  }

  @POST
  @Path("/{peerId}/revoke")
  @RolesAllowed({Roles.ADMIN, Roles.ADMIN_AGENT, Roles.DESK_ADMIN, Roles.DESK, Roles.SYSTEM})
  @Operation(operationId = "revokePeer", summary = "Revoke a peer; idempotent on a revoked one")
  @APIResponse(responseCode = "200", description = "The peer, REVOKED")
  @APIResponse(responseCode = "404", description = "No such peer")
  public PeerDto revoke(@PathParam("peerId") UUID peerId) {
    return PeerDto.of(registry.revoke(peerId, Callers.subject(identity), RevokeReason.REVOKED));
  }

  @GET
  @Path("/{peerId}/config")
  @Produces(MediaType.TEXT_PLAIN)
  @Operation(
      operationId = "getPeerConfig",
      summary = "The peer's wg-quick config, with a literal <PRIVATE_KEY> placeholder")
  @APIResponse(responseCode = "200", description = "The config")
  @APIResponse(responseCode = "404", description = "No such peer")
  @APIResponse(responseCode = "409", description = "No private edge has announced itself")
  public String config(@PathParam("peerId") UUID peerId) {
    NaPeer peer = registry.get(peerId);
    return PeerConfig.render(peer, edge());
  }

  @GET
  @Path("/{peerId}/app-config")
  @Produces(MediaType.TEXT_PLAIN)
  @Operation(
      operationId = "getPeerAppConfig",
      summary =
          "The peer's config for the WireGuard apps, with a literal <PRIVATE_KEY> placeholder")
  @APIResponse(responseCode = "200", description = "The config")
  @APIResponse(responseCode = "404", description = "No such peer")
  @APIResponse(responseCode = "409", description = "No private edge has announced itself")
  public String appConfig(@PathParam("peerId") UUID peerId) {
    NaPeer peer = registry.get(peerId);
    return PeerConfig.renderApp(peer, edge());
  }

  @GET
  @Path("/{peerId}/tunnel")
  @Operation(
      operationId = "getPeerTunnel",
      summary = "The peer's tunnel parameters, the companion's input")
  @APIResponse(responseCode = "200", description = "The tunnel parameters")
  @APIResponse(responseCode = "404", description = "No such peer")
  @APIResponse(responseCode = "409", description = "No private edge has announced itself")
  public TunnelDto tunnel(@PathParam("peerId") UUID peerId) {
    NaPeer peer = registry.get(peerId);
    return new TunnelDto(peer.id, peer.tunnelIp, EdgeDto.of(edge()));
  }

  private NaPrivateEdge edge() {
    return registry.privateEdge().orElseThrow(() -> NetworkAccessException.conflict(NO_EDGE));
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw NetworkAccessException.badRequest("unknown " + name + " " + value);
    }
  }
}
