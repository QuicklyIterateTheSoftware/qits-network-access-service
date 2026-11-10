package eu.wohlben.qits.networkaccess.control;

import eu.wohlben.qits.networkaccess.entity.NaPeer;
import eu.wohlben.qits.networkaccess.entity.NaPrivateEdge;

/**
 * The {@code wg-quick} config of one peer, for clients outside the qits CLI.
 *
 * <p>Three choices are deliberate:
 *
 * <ul>
 *   <li><b>No {@code DNS =} line.</b> wg-quick would send every query to the tunnel resolver, which
 *       REFUSES names outside the zone, and the client would lose the internet. Split DNS is set
 *       with {@code resolvectl} in PostUp instead, for the zone alone.
 *   <li><b>{@code <PRIVATE_KEY>} is literal.</b> The service never sees a private key; the UI
 *       replaces the placeholder in the browser, and the node helper never reads this text.
 *   <li><b>{@code AllowedIPs} is the edge's address alone</b>: only the private edge is reached
 *       through the tunnel.
 * </ul>
 */
public final class PeerConfig {

  /** The placeholder the client replaces with its own key. */
  public static final String PRIVATE_KEY_PLACEHOLDER = "<PRIVATE_KEY>";

  private PeerConfig() {}

  public static String render(NaPeer peer, NaPrivateEdge edge) {
    return "# qits network access: "
        + peer.name
        + " ("
        + peer.id
        + "), "
        + peer.kind
        + "\n"
        + "# Linux: save as /etc/wireguard/qits.conf, then `wg-quick up qits`. Needs"
        + " systemd-resolved for split DNS.\n"
        + "[Interface]\n"
        + "PrivateKey = "
        + PRIVATE_KEY_PLACEHOLDER
        + "\n"
        + "Address = "
        + peer.tunnelIp
        + "/32\n"
        + "MTU = "
        + edge.mtu
        + "\n"
        + "PostUp = resolvectl dns %i "
        + edge.dnsAddress
        + "; resolvectl domain %i ~"
        + edge.domain
        + "\n"
        + "PreDown = resolvectl revert %i\n"
        + "\n"
        + "[Peer]\n"
        + "PublicKey = "
        + edge.publicKey
        + "\n"
        + "Endpoint = "
        + edge.publicAddress
        + ":"
        + edge.listenPort
        + "\n"
        + "AllowedIPs = "
        + edge.dnsAddress
        + "/32\n"
        + "PersistentKeepalive = 25\n";
  }
}
