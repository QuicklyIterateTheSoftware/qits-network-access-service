package eu.wohlben.qits.networkaccess.control;

import eu.wohlben.qits.networkaccess.entity.NaPeer;
import eu.wohlben.qits.networkaccess.entity.PeerStatus;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The tunnel address allocation: the lowest address of the pool ({@code
 * qits.network-access.tunnel.cidr} from host {@code qits.network-access.tunnel.first-host}) that no
 * ACTIVE peer holds. A revoked peer's address is free again at once.
 *
 * <p>{@link #next()} runs inside the insert's transaction and reads the ACTIVE addresses; two
 * registrations racing for the same address are settled by the partial unique index {@code
 * na_peer_active_tunnel_ip}, and {@link PeerRegistry} retries the loser in a new transaction. A full
 * pool is a 409.
 */
@ApplicationScoped
public class PeerAddresses {

  /** The 409 a full pool answers. */
  public static final String EXHAUSTED = "the tunnel address pool is exhausted";

  private final TunnelNetwork network;

  public PeerAddresses(
      @ConfigProperty(name = "qits.network-access.tunnel.cidr") String cidr,
      @ConfigProperty(name = "qits.network-access.tunnel.first-host") int firstHost) {
    this.network = TunnelNetwork.parse(cidr, firstHost);
  }

  /** The tunnel network this allocation draws from. */
  public TunnelNetwork network() {
    return network;
  }

  /** The lowest free address, in the caller's transaction. */
  public String next() {
    Set<String> taken = new HashSet<>();
    NaPeer.<NaPeer>stream("status", PeerStatus.ACTIVE).forEach(peer -> taken.add(peer.tunnelIp));
    return lowestFree(taken).orElseThrow(() -> NetworkAccessException.conflict(EXHAUSTED));
  }

  /** The lowest allocatable address not in {@code taken}; empty when every one is taken. */
  public Optional<String> lowestFree(Collection<String> taken) {
    Set<String> held = new HashSet<>(taken);
    return network.allocatable().stream().filter(address -> !held.contains(address)).findFirst();
  }

  /** Whether {@code address} is in the allocatable pool (a kept address must be). */
  public boolean allocatable(String address) {
    return network.allocatable().contains(address);
  }
}
