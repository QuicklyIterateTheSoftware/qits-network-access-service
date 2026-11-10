package eu.wohlben.qits.networkaccess.control;

/**
 * A refusal the domain states and the REST layer turns into a status: {@link Kind#BAD_REQUEST} is
 * 400, {@link Kind#FORBIDDEN} 403, {@link Kind#NOT_FOUND} 404, {@link Kind#CONFLICT} 409 and {@link
 * Kind#UNAVAILABLE} 503. The message is the answer's text, so
 * it never carries a key.
 */
public class NetworkAccessException extends RuntimeException {

  /** Which status the refusal is. */
  public enum Kind {
    BAD_REQUEST,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    UNAVAILABLE
  }

  private final Kind kind;

  public NetworkAccessException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  public static NetworkAccessException badRequest(String message) {
    return new NetworkAccessException(Kind.BAD_REQUEST, message);
  }

  public static NetworkAccessException forbidden(String message) {
    return new NetworkAccessException(Kind.FORBIDDEN, message);
  }

  public static NetworkAccessException unavailable(String message) {
    return new NetworkAccessException(Kind.UNAVAILABLE, message);
  }

  public static NetworkAccessException notFound(String message) {
    return new NetworkAccessException(Kind.NOT_FOUND, message);
  }

  public static NetworkAccessException conflict(String message) {
    return new NetworkAccessException(Kind.CONFLICT, message);
  }
}
