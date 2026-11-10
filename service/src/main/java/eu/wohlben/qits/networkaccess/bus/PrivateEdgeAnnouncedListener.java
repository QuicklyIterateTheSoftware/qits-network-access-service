package eu.wohlben.qits.networkaccess.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.networkaccess.control.PrivateEdges;
import eu.wohlben.qits.networkaccess.control.PrivateEdges.Announcement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@code PrivateEdgeAnnounced} (qits-edge-private, epic qits-1977) into {@code na_private_edge}: one
 * row per edge application, kept only when the frame's {@code occurredAt} is newer than the row's
 * ({@link PrivateEdges}). Every config and tunnel answer reads it, and nothing else seeds it, so the
 * consumer replays from the epoch.
 */
@ApplicationScoped
public class PrivateEdgeAnnouncedListener implements QitsDurableEventListener {

  static final String SIGNATURE = "PrivateEdgeAnnounced";
  static final String CONSUMER_ID = "network-access-private-edge";

  private static final Logger LOG = Logger.getLogger(PrivateEdgeAnnouncedListener.class);

  /**
   * The payload, a private wire DTO rather than a dependency on the edge's jar; unknown fields are
   * ignored. The ports are boxed so a missing one reads as missing, not as 0.
   */
  public record PrivateEdgeAnnouncedPayload(
      String application,
      String publicKey,
      String publicAddress,
      Integer listenPort,
      String tunnelAddress,
      String tunnelCidr,
      String dnsAddress,
      Integer mtu,
      String domain) {}

  @Inject PrivateEdges edges;

  @ConfigProperty(name = "qits.network-access.environment")
  String environment;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  @Override
  public boolean replayFromEpoch() {
    return true;
  }

  /** Only this environment's edge: another environment's tunnel is not the one handed out here. */
  @Override
  public boolean selects(EventFrame frame) {
    return Frames.ours(frame, environment);
  }

  @Override
  public void onFrame(EventFrame frame) {
    PrivateEdgeAnnouncedPayload p =
        Frames.payload(frame, PrivateEdgeAnnouncedPayload.class, LOG).orElse(null);
    if (p == null) {
      return;
    }
    UUID event = Frames.uuid(frame.id());
    if (event == null
        || frame.occurredAt() == null
        || blank(p.application())
        || blank(p.publicKey())
        || blank(p.publicAddress())
        || p.listenPort() == null
        || blank(p.tunnelAddress())
        || blank(p.tunnelCidr())
        || blank(p.dnsAddress())
        || p.mtu() == null
        || blank(p.domain())) {
      LOG.warnf(
          "%s %s misses a field of the announcement; it is settled unhandled",
          frame.name(), frame.id());
      return;
    }
    boolean stored =
        edges.announce(
            new Announcement(
                p.application(),
                p.publicKey(),
                p.publicAddress(),
                p.listenPort(),
                p.tunnelAddress(),
                p.tunnelCidr(),
                p.dnsAddress(),
                p.mtu(),
                p.domain(),
                // What timestamptz keeps, so a replay of the same frame compares equal.
                frame.occurredAt().truncatedTo(ChronoUnit.MICROS),
                event));
    if (!stored) {
      LOG.debugf(
          "%s %s is not newer than %s's stored announcement",
          frame.name(), frame.id(), p.application());
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
