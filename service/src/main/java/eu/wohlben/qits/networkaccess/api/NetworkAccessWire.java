package eu.wohlben.qits.networkaccess.api;

import eu.wohlben.qits.networkaccess.entity.NaNode;
import eu.wohlben.qits.networkaccess.entity.NaPeer;
import eu.wohlben.qits.networkaccess.entity.NaPrivateEdge;
import java.time.Instant;
import java.util.UUID;

/**
 * Every shape this API serves or reads, in one place, and each one registered for reflection in
 * {@link ApiWireReflection} (ApiWireReflectionTest walks this class's nested types).
 */
public final class NetworkAccessWire {

  private NetworkAccessWire() {}

  /** {@code POST /peers}: a device's name and the public key it generated itself. */
  public record CreatePeerRequest(String name, String publicKey) {}

  /**
   * {@code POST /nodes/{targetId}/join}: the node's name (its host name; qits-system's target name
   * wins when the registration check answers one) and the public key it generated itself.
   */
  public record JoinNodeRequest(String name, String publicKey) {}

  /** One peer. {@code ownerUserId} is set for a DEVICE, {@code targetId} for a NODE. */
  public record PeerDto(
      UUID id,
      String kind,
      String name,
      String publicKey,
      String tunnelIp,
      String ownerUserId,
      UUID targetId,
      String status,
      Instant createdAt,
      String createdBy,
      Instant revokedAt,
      String revokedBy,
      String revokeReason) {

    static PeerDto of(NaPeer peer) {
      return new PeerDto(
          peer.id,
          peer.kind.name(),
          peer.name,
          peer.publicKey,
          peer.tunnelIp,
          peer.ownerUserId,
          peer.targetId,
          peer.status.name(),
          peer.createdAt,
          peer.createdBy,
          peer.revokedAt,
          peer.revokedBy,
          peer.revokeReason == null ? null : peer.revokeReason.name());
    }
  }

  /** A joined node, with its ACTIVE peer (null when it has none, e.g. after its target went). */
  public record NodeDto(
      UUID targetId,
      String name,
      Instant createdAt,
      Instant lastJoinedAt,
      Instant deletedAt,
      PeerDto peer) {

    static NodeDto of(NaNode node, NaPeer peer) {
      return new NodeDto(
          node.targetId,
          node.name,
          node.createdAt,
          node.lastJoinedAt,
          node.deletedAt,
          peer == null ? null : PeerDto.of(peer));
    }
  }

  /** The edge half of a tunnel answer: what a client needs to reach the private edge. */
  public record EdgeDto(
      String publicKey,
      String publicAddress,
      int listenPort,
      String dnsAddress,
      String tunnelCidr,
      int mtu,
      String domain) {

    static EdgeDto of(NaPrivateEdge edge) {
      return new EdgeDto(
          edge.publicKey,
          edge.publicAddress,
          edge.listenPort,
          edge.dnsAddress,
          edge.tunnelCidr,
          edge.mtu,
          edge.domain);
    }
  }

  /**
   * {@code GET /peers/{id}/tunnel}, and the answer a node join gives (section 5): the companion's
   * and the node helper's input.
   */
  public record TunnelDto(UUID peerId, String tunnelIp, EdgeDto edge) {}

  /** {@code GET /private-edge}: the announcement as it was stored. */
  public record PrivateEdgeDto(
      String application,
      String publicKey,
      String publicAddress,
      int listenPort,
      String tunnelAddress,
      String tunnelCidr,
      String dnsAddress,
      int mtu,
      String domain,
      Instant announcedAt) {

    static PrivateEdgeDto of(NaPrivateEdge edge) {
      return new PrivateEdgeDto(
          edge.application,
          edge.publicKey,
          edge.publicAddress,
          edge.listenPort,
          edge.tunnelAddress,
          edge.tunnelCidr,
          edge.dnsAddress,
          edge.mtu,
          edge.domain,
          edge.announcedAt);
    }
  }

  /**
   * {@code GET /companion}: how the CLI runs the companion — this service's own image, at exactly
   * this service's version, started with the companion's {@code entrypoint} as {@code user} (the
   * uid that owns the image's empty {@code /state}).
   */
  public record CompanionDto(String image, String version, String entrypoint, String user) {}

  /** Every refusal's body. */
  public record ErrorBody(String message) {}
}
