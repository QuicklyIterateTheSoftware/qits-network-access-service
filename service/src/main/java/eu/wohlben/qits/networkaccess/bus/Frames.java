package eu.wohlben.qits.networkaccess.bus;

import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import java.util.Optional;
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
