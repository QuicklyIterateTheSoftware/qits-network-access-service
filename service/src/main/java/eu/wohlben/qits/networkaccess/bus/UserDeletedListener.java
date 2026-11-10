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
 * {@code UserDeleted} (qits-idp): a deleted person's devices lose the tunnel. Every ACTIVE DEVICE
 * peer whose owner is the user's id — the {@code sub} every token of theirs carried, which is what
 * {@code POST /peers} stored — is revoked {@code USER_DELETED} by {@value #REVOKED_BY}. Idempotent:
 * a replay finds nothing ACTIVE. It replays from the epoch so that a deletion this service missed
 * while it did not exist yet is still honoured.
 */
@ApplicationScoped
public class UserDeletedListener implements QitsDurableEventListener {

  static final String SIGNATURE = "UserDeleted";
  static final String CONSUMER_ID = "network-access-user-deleted";
  static final String REVOKED_BY = "qits-idp";

  private static final Logger LOG = Logger.getLogger(UserDeletedListener.class);

  /** The payload, a private wire DTO; {@code username} only for the log. */
  public record UserDeletedPayload(String userId, String username) {}

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
    UserDeletedPayload p = Frames.payload(frame, UserDeletedPayload.class, LOG).orElse(null);
    if (p == null) {
      return;
    }
    UUID user = Frames.uuid(p.userId());
    if (user == null) {
      LOG.warnf("%s %s names no user id; it is settled unhandled", frame.name(), frame.id());
      return;
    }
    int revoked =
        Frames.ownTransaction(() -> registry.revokeDevicesOf(user.toString(), REVOKED_BY));
    if (revoked > 0) {
      LOG.infof(
          "revoked %d device peers of the deleted user %s (%s) on %s %s",
          revoked, user, p.username(), frame.name(), frame.id());
    }
  }
}
