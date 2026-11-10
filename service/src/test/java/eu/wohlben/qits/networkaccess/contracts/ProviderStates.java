package eu.wohlben.qits.networkaccess.contracts;

import eu.wohlben.qits.networkaccess.support.Fixtures;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>qits-network-access's provider states</b>: each one clears the store, stages what one consumer
 * situation needs and hands back its parameters. Two callers: {@link GoldenMasterRecordingTest}
 * runs a state before each operation that names it, and {@link ConsumerPactVerificationTest}'s
 * {@code @State} methods delegate here.
 *
 * <p>Every state starts from an empty store, so applying it twice is the same state, and the ids
 * it stages are fixed — the recording freezes them anyway ({@link Freezer}), but a fixed id keeps a
 * failing diff readable.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String AN_ANNOUNCED_PRIVATE_EDGE = "an announced private edge";

  public static final String A_DEVICE_PEER = "a device peer behind an announced private edge";

  public static final String A_JOINED_NODE = "a joined node";

  /** Who the recordings call as, and who owns the staged device: the operator's own {@code sub}. */
  public static final String OPERATOR = "operator";

  static final UUID DEVICE_PEER = UUID.fromString("0e6b4f39-6f0e-4a55-9a1e-0c3a1e7c2a11");

  static final UUID NODE_TARGET = UUID.fromString("4f2d9c71-8a3e-4b5f-9d6c-7e8f9a0b1c2d");

  static final UUID NODE_PEER = UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

  /** What a state hands back: its parameters, keys sorted, and no unique tokens. */
  public record Setup(Map<String, String> params) {
    public List<String> uniqueTokens() {
      return List.of();
    }
  }

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  @Inject Fixtures fixtures;

  public ProviderStates() {
    states.put(AN_ANNOUNCED_PRIVATE_EDGE, this::anAnnouncedPrivateEdge);
    states.put(A_DEVICE_PEER, this::aDevicePeer);
    states.put(A_JOINED_NODE, this::aJoinedNode);
  }

  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  /** No peers yet, and the private edge has announced itself: a device can be added. */
  private Setup anAnnouncedPrivateEdge() {
    fixtures.clear();
    fixtures.edge();
    Map<String, String> params = new TreeMap<>();
    params.put("publicKey", Fixtures.key(2));
    return new Setup(Collections.unmodifiableMap(params));
  }

  /** The operator's ACTIVE device at 100.96.0.10, behind the announced edge. */
  private Setup aDevicePeer() {
    fixtures.clear();
    fixtures.edge();
    fixtures.device(DEVICE_PEER, "laptop", Fixtures.key(3), "100.96.0.10", OPERATOR);
    Map<String, String> params = new TreeMap<>();
    params.put("peerId", DEVICE_PEER.toString());
    return new Setup(Collections.unmodifiableMap(params));
  }

  /** A target that joined as a node, with its ACTIVE NODE peer at 100.96.0.11. */
  private Setup aJoinedNode() {
    fixtures.clear();
    fixtures.edge();
    fixtures.node(NODE_TARGET, "node-a", NODE_PEER, Fixtures.key(4), "100.96.0.11");
    Map<String, String> params = new TreeMap<>();
    params.put("peerId", NODE_PEER.toString());
    params.put("targetId", NODE_TARGET.toString());
    return new Setup(Collections.unmodifiableMap(params));
  }
}
