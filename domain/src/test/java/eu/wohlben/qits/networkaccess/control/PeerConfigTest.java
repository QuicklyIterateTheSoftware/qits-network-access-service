package eu.wohlben.qits.networkaccess.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.networkaccess.entity.NaPeer;
import eu.wohlben.qits.networkaccess.entity.NaPrivateEdge;
import eu.wohlben.qits.networkaccess.entity.PeerKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The config exactly as the design page writes it, and no {@code DNS =} line. */
class PeerConfigTest {

  @Test
  void theConfigIsTheDesignPagesText() {
    NaPeer peer = new NaPeer();
    peer.id = UUID.fromString("0e6b4f39-6f0e-4a55-9a1e-0c3a1e7c2a11");
    peer.kind = PeerKind.DEVICE;
    peer.name = "laptop";
    peer.tunnelIp = "100.96.0.10";
    NaPrivateEdge edge = new NaPrivateEdge();
    edge.publicKey = "EDGEKEYEDGEKEYEDGEKEYEDGEKEYEDGEKEYEDGEKEY0=";
    edge.publicAddress = "46.224.171.33";
    edge.listenPort = 47812;
    edge.dnsAddress = "100.96.0.1";
    edge.mtu = 1380;
    edge.domain = "wohlben.eu";

    String config = PeerConfig.render(peer, edge);

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
}
