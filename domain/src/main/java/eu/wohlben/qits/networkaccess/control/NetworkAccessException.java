package eu.wohlben.qits.networkaccess.control;

/**
 * A refusal the domain states and the REST layer turns into a status: {@link Kind#BAD_REQUEST} is
 * 400, {@link Kind#NOT_FOUND} 404, {@link Kind#CONFLICT} 409. The message is the answer's text, so
 * it never carries a key.
 */
public class NetworkAccessException extends RuntimeException {

  /** Which status the refusal is. */
  public enum Kind {
    BAD_REQUEST,
    NOT_FOUND,
    CONFLICT
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

  public static NetworkAccessException notFound(String message) {
    return new NetworkAccessException(Kind.NOT_FOUND, message);
  }

  public static NetworkAccessException conflict(String message) {
    return new NetworkAccessException(Kind.CONFLICT, message);
  }
}
