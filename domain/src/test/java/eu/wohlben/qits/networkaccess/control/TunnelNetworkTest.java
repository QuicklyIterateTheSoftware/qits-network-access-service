package eu.wohlben.qits.networkaccess.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The pool arithmetic: 100.96.0.0/24 from host 10 is .10 to .254, 245 addresses. */
class TunnelNetworkTest {

  @Test
  void theShippedPoolIsTenToTwoFiftyFour() {
    List<String> pool = TunnelNetwork.parse("100.96.0.0/24", 10).allocatable();
    assertEquals(245, pool.size());
    assertEquals("100.96.0.10", pool.getFirst());
    assertEquals("100.96.0.254", pool.getLast());
  }

  @Test
  void lowestFreeSkipsTakenAddresses() {
    PeerAddresses addresses = new PeerAddresses("100.96.0.0/24", 10);
    assertEquals("100.96.0.10", addresses.lowestFree(List.of()).orElseThrow());
    assertEquals(
        "100.96.0.12",
        addresses.lowestFree(List.of("100.96.0.10", "100.96.0.11", "100.96.0.2")).orElseThrow());
    assertEquals(
        "100.96.0.11", addresses.lowestFree(List.of("100.96.0.10", "100.96.0.12")).orElseThrow());
  }

  @Test
  void aFullPoolHasNoFreeAddress() {
    PeerAddresses addresses = new PeerAddresses("100.96.0.0/24", 10);
    assertEquals(
        true, addresses.lowestFree(addresses.network().allocatable()).isEmpty(), "full pool");
  }

  @Test
  void aNonNetworkAddressOrAnOutOfRangeFirstHostIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> TunnelNetwork.parse("100.96.0.1/24", 10));
    assertThrows(IllegalArgumentException.class, () -> TunnelNetwork.parse("100.96.0.0/24", 255));
    assertThrows(IllegalArgumentException.class, () -> TunnelNetwork.parse("100.96.0/24", 10));
  }
}
