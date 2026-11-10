package eu.wohlben.qits.networkaccess.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Names are DNS labels; a key is 44 base64 characters decoding to 32 bytes; anything else is 400. */
class PeerValidationTest {

  static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  void dnsLabelsPass() {
    for (String name : new String[] {"a", "laptop", "work-laptop-2", "x".repeat(63)}) {
      assertEquals(name, PeerValidation.name(name));
    }
  }

  @Test
  void otherNamesAre400() {
    for (String name :
        new String[] {null, "", "-laptop", "laptop-", "Laptop", "my_laptop", "a.b", "x".repeat(64)}) {
      NetworkAccessException refusal =
          assertThrows(NetworkAccessException.class, () -> PeerValidation.name(name));
      assertEquals(NetworkAccessException.Kind.BAD_REQUEST, refusal.kind());
    }
  }

  @Test
  void aWireguardPublicKeyPasses() {
    assertEquals(44, KEY.length());
    assertEquals(KEY, PeerValidation.publicKey(KEY));
  }

  @Test
  void otherKeysAre400AndTheMessageNeverEchoesTheKey() {
    String thirtyOneBytes = Base64.getEncoder().encodeToString(new byte[31]) + "=";
    for (String key :
        new String[] {
          null, "", KEY.substring(1), KEY + "A", "!" + KEY.substring(1), thirtyOneBytes
        }) {
      NetworkAccessException refusal =
          assertThrows(NetworkAccessException.class, () -> PeerValidation.publicKey(key));
      assertEquals(NetworkAccessException.Kind.BAD_REQUEST, refusal.kind());
      if (key != null && !key.isEmpty()) {
        assertFalse(refusal.getMessage().contains(key), refusal.getMessage());
      }
    }
  }
}
