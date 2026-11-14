package eu.wohlben.qits.networkaccess.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.networkaccess.entity.NaPeer;
import eu.wohlben.qits.networkaccess.entity.NaPrivateEdge;
import eu.wohlben.qits.networkaccess.entity.PeerKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Both configs exactly as the design page writes them: the wg-quick one with no {@code DNS =} line,
 * the apps' one with no PostUp or PreDown.
 */
class PeerConfigTest {

  @Test
  void theConfigIsTheDesignPagesText() {
    String config = PeerConfig.render(peer(), edge());

    assertEquals(
        """
        # qits network access: laptop (0e6b4f39-6f0e-4a55-9a1e-0c3a1e7c2a11), DEVICE
        # Linux: save as /etc/wireguard/qits.conf, then `wg-quick up qits`. Needs systemd-resolved for split DNS.
        [Interface]
        PrivateKey = <PRIVATE_KEY>
        Address = 100.96.0.10/32
        MTU = 1380
        PostUp = resolvectl dns %i 100.96.0.1; resolvectl domain %i ~wohlben.eu
        PreDown = resolvectl revert %i

        [Peer]
        PublicKey = EDGEKEYEDGEKEYEDGEKEYEDGEKEYEDGEKEYEDGEKEY0=
        Endpoint = 46.224.171.33:47812
        AllowedIPs = 100.96.0.1/32
        PersistentKeepalive = 25
        """,
        config);
    assertFalse(config.contains("DNS ="), "no DNS line: split DNS only");
  }

  @Test
  void theAppConfigHasADnsLineAndNoHooks() {
    String config = PeerConfig.renderApp(peer(), edge());

    assertEquals(
        """
        # qits network access: laptop (0e6b4f39-6f0e-4a55-9a1e-0c3a1e7c2a11), DEVICE
        [Interface]
        PrivateKey = <PRIVATE_KEY>
        Address = 100.96.0.10/32
        DNS = 100.96.0.1
        MTU = 1380

        [Peer]
        PublicKey = EDGEKEYEDGEKEYEDGEKEYEDGEKEYEDGEKEYEDGEKEY0=
        Endpoint = 46.224.171.33:47812
        AllowedIPs = 100.96.0.1/32
        PersistentKeepalive = 25
        """,
        config);
    assertFalse(config.contains("PostUp"), "the apps run no hooks");
    assertFalse(config.contains("PreDown"), "the apps run no hooks");
    assertTrue(config.contains(PeerConfig.PRIVATE_KEY_PLACEHOLDER));
  }

  private static NaPeer peer() {
    NaPeer peer = new NaPeer();
    peer.id = UUID.fromString("0e6b4f39-6f0e-4a55-9a1e-0c3a1e7c2a11");
    peer.kind = PeerKind.DEVICE;
    peer.name = "laptop";
    peer.tunnelIp = "100.96.0.10";
    return peer;
  }

  private static NaPrivateEdge edge() {
    NaPrivateEdge edge = new NaPrivateEdge();
    edge.publicKey = "EDGEKEYEDGEKEYEDGEKEYEDGEKEYEDGEKEYEDGEKEY0=";
    edge.publicAddress = "46.224.171.33";
    edge.listenPort = 47812;
    edge.dnsAddress = "100.96.0.1";
    edge.mtu = 1380;
    edge.domain = "wohlben.eu";
    return edge;
  }
}
