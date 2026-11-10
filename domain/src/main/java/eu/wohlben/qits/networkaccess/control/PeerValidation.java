package eu.wohlben.qits.networkaccess.control;

import java.util.Base64;
import java.util.regex.Pattern;

/**
 * What a peer's name and public key must be; anything else is a 400.
 *
 * <ul>
 *   <li>A name is a DNS label: {@code ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$}.
 *   <li>A public key is 44 characters of base64 that decode to 32 bytes, a Curve25519 key as
 *       {@code wg genkey | wg pubkey} prints it.
 * </ul>
 */
public final class PeerValidation {

  static final Pattern NAME = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

  private PeerValidation() {}

  /** The name, or a 400 naming the rule. */
  public static String name(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw NetworkAccessException.badRequest(
          "name must be a DNS label: lower-case letters, digits and inner dashes, at most 63");
    }
    return name;
  }

  /** The public key, or a 400 that never echoes it. */
  public static String publicKey(String key) {
    if (key == null || key.length() != 44) {
      throw NetworkAccessException.badRequest(
          "publicKey must be 44 characters of base64 (a 32-byte WireGuard public key)");
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(key);
    } catch (IllegalArgumentException e) {
      throw NetworkAccessException.badRequest("publicKey is not base64");
    }
    if (decoded.length != 32) {
      throw NetworkAccessException.badRequest("publicKey must decode to 32 bytes");
    }
    return key;
  }
}
