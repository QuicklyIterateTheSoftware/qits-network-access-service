package eu.wohlben.qits.networkaccess.control;

import eu.wohlben.qits.networkaccess.entity.NaPrivateEdge;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * What each private edge last announced about itself ({@code PrivateEdgeAnnounced}, published by
 * {@code qits-edge-private}): one {@code na_private_edge} row per edge application, replaced only by
 * a strictly newer announcement. The newest by the frame's {@code occurredAt} wins, so a replay
 * from the epoch, or an old frame arriving late, never rolls the endpoint back.
 */
@ApplicationScoped
public class PrivateEdges {

  private static final Logger LOG = Logger.getLogger(PrivateEdges.class);

  /** One announcement, as the consumer decoded it; every field is required. */
  public record Announcement(
      String application,
      String publicKey,
      String publicAddress,
      int listenPort,
      String tunnelAddress,
      String tunnelCidr,
      String dnsAddress,
      int mtu,
      String domain,
      Instant announcedAt,
      UUID eventId) {}

  /**
   * Stores {@code announcement} unless the row already holds one at least as new.
   *
   * @return whether the row was written
   */
  @Transactional
  public boolean announce(Announcement announcement) {
    NaPrivateEdge edge = NaPrivateEdge.findById(announcement.application());
    if (edge != null && !announcement.announcedAt().isAfter(edge.announcedAt)) {
      return false;
    }
    boolean fresh = edge == null;
    if (fresh) {
      edge = new NaPrivateEdge();
      edge.application = announcement.application();
    }
    edge.publicKey = announcement.publicKey();
    edge.publicAddress = announcement.publicAddress();
    edge.listenPort = announcement.listenPort();
    edge.tunnelAddress = announcement.tunnelAddress();
    edge.tunnelCidr = announcement.tunnelCidr();
    edge.dnsAddress = announcement.dnsAddress();
    edge.mtu = announcement.mtu();
    edge.domain = announcement.domain();
    edge.announcedAt = announcement.announcedAt();
    edge.eventId = announcement.eventId();
    if (fresh) {
      edge.persist();
    }
    LOG.infof(
        "the private edge %s announced %s:%d (event %s)",
        edge.application,
        edge.publicAddress,
        edge.listenPort,
        edge.eventId);
    return true;
  }
}
