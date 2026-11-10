package eu.wohlben.qits.networkaccess.bus;

import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * What the three consumers share: decoding a frame's payload into a private wire DTO, and settling a
 * frame that cannot be decoded. A frame refused for its own content is refused identically on every
 * catch-up, so it is logged and settled — never thrown, which would pin the consumer's watermark
 * before every later event (the edge's {@code DeploymentActiveSubscriber} does the same).
 *
 * <p>The payload carries neither {@code eventId} nor {@code occurredAt}: both ride the envelope, as
 * {@link EventFrame#id()} and {@link EventFrame#occurredAt()}. The library also omits null fields
 * and hides any field called {@code name}, so no DTO here reads one.
 *
 * <p><b>Every handler writes in a transaction of its own</b> ({@link #ownTransaction}). The library
 * calls {@code onFrame} inside its claim transaction, whose {@code consumed_event} insert is on the
 * {@code eventstream} datasource; this service's rows are on {@code networkaccess}, a different
 * database. Both are non-XA, and Narayana admits one one-phase resource per transaction, so a
 * handler write that JOINED the claim's transaction failed to enlist ("Exception in association of
 * connection to existing transaction"), rolled the claim back and left the event owed for good
 * (live on 2026.1110.191501). In its own transaction the effect commits first and the claim after
 * it; a crash between the two re-delivers the event, which every handler here survives because it
 * is idempotent — the same shape as qits-edge's projections ({@code REQUIRES_NEW}).
 */
final class Frames {

  private Frames() {}

  /** The payload as {@code type}, or empty (logged) when it does not decode. */
  static <T> Optional<T> payload(EventFrame frame, Class<T> type, Logger log) {
    try {
      return Optional.ofNullable(CanonicalJson.payloadTo(frame.payload(), type));
    } catch (RuntimeException unreadable) {
      log.warnf(
          "%s %s carried an unreadable payload: %s; it is settled unhandled",
          frame.name(), frame.id(), unreadable.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Runs a handler's write in a new transaction, suspending the library's claim transaction around
   * it; see the class comment.
   */
  static <T> T ownTransaction(Supplier<T> write) {
    return QuarkusTransaction.requiringNew().call(write::get);
  }

  /** {@code value} as a UUID, or null when it is none. */
  static UUID uuid(String value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value.strip());
    } catch (IllegalArgumentException notOne) {
      return null;
    }
  }

  /** Whether {@code frame} is of {@code environment}, this service's own. */
  static boolean ours(EventFrame frame, String environment) {
    return environment != null && environment.equals(frame.environment());
  }
}
