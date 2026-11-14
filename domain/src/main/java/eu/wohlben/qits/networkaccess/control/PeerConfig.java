package eu.wohlben.qits.networkaccess.control;

import eu.wohlben.qits.networkaccess.entity.NaPeer;
import eu.wohlben.qits.networkaccess.entity.NaPrivateEdge;

/**
 * The WireGuard config of one peer, for clients outside the qits CLI, in two renderings.
 *
 * <ul>
 *   <li>{@link #render} is the {@code wg-quick} config, for Linux: split DNS through {@code
 *       resolvectl} in PostUp and PreDown.
 *   <li>{@link #renderApp} is the WireGuard apps' config (Android, iOS, macOS, Windows). The apps
 *       run no PostUp or PreDown, and Android and iOS refuse an interface key they do not know at
 *       import, so it carries neither, and a {@code DNS =} line instead.
 * </ul>
 *
 * <p>Four choices are deliberate:
 *
 * <ul>
 *   <li><b>No {@code DNS =} line in {@link #render}.</b> wg-quick would send every query to the
 *       tunnel resolver, and a Linux client has split DNS to keep the internet's names off the
 *       tunnel, so it is set with {@code resolvectl} in PostUp instead, for the zone alone.
 *   <li><b>A {@code DNS =} line in {@link #renderApp}.</b> The apps have no split DNS, and the
 *       tunnel resolver forwards a name outside the zone (qits-2262), so sending every query to it
 *       keeps the client on the internet.
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

  /** The {@code wg-quick} config: split DNS in PostUp and PreDown, no {@code DNS =} line. */
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

  /** The WireGuard apps' config: a {@code DNS =} line, no PostUp or PreDown, no Linux hint. */
  public static String renderApp(NaPeer peer, NaPrivateEdge edge) {
    return "# qits network access: "
        + peer.name
        + " ("
        + peer.id
        + "), "
        + peer.kind
        + "\n"
        + "[Interface]\n"
        + "PrivateKey = "
        + PRIVATE_KEY_PLACEHOLDER
        + "\n"
        + "Address = "
        + peer.tunnelIp
        + "/32\n"
        + "DNS = "
        + edge.dnsAddress
        + "\n"
        + "MTU = "
        + edge.mtu
        + "\n"
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
