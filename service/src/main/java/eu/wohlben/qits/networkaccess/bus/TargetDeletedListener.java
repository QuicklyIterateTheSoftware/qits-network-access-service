package eu.wohlben.qits.networkaccess.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.networkaccess.control.PeerRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@code TargetDeleted} (qits-system): a decommissioned target's node leaves the tunnel. Its ACTIVE
 * NODE peer is revoked {@code NODE_DELETED} by {@value #REVOKED_BY} and {@code na_node.deleted_at}
 * is set. A target that never joined is nothing here. Idempotent, and replayed from the epoch.
 */
@ApplicationScoped
public class TargetDeletedListener implements QitsDurableEventListener {

  static final String SIGNATURE = "TargetDeleted";
  static final String CONSUMER_ID = "network-access-target-deleted";
  static final String REVOKED_BY = "qits-system";

  private static final Logger LOG = Logger.getLogger(TargetDeletedListener.class);

  /** The payload, a private wire DTO; {@code targetName} only for the log. */
  public record TargetDeletedPayload(String targetId, String targetName) {}

  @Inject PeerRegistry registry;

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

  @Override
  public boolean selects(EventFrame frame) {
    return Frames.ours(frame, environment);
  }

  @Override
  public void onFrame(EventFrame frame) {
    TargetDeletedPayload p = Frames.payload(frame, TargetDeletedPayload.class, LOG).orElse(null);
    if (p == null) {
      return;
    }
    UUID target = Frames.uuid(p.targetId());
    if (target == null) {
      LOG.warnf("%s %s names no target id; it is settled unhandled", frame.name(), frame.id());
      return;
    }
    if (registry.nodeDeleted(target, REVOKED_BY)) {
      LOG.infof(
          "the node %s (%s) left the tunnel on %s %s",
          target, p.targetName(), frame.name(), frame.id());
    }
  }
}
