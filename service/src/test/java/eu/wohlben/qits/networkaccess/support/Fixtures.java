package eu.wohlben.qits.networkaccess.support;

import eu.wohlben.qits.networkaccess.entity.NaNode;
import eu.wohlben.qits.networkaccess.entity.NaPeer;
import eu.wohlben.qits.networkaccess.entity.NaPrivateEdge;
import eu.wohlben.qits.networkaccess.entity.PeerKind;
import eu.wohlben.qits.networkaccess.entity.PeerStatus;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Rows the suites arrange directly, and the fake bus they read the published events off. Every
 * suite starts from {@link #clear()}: the store is shared by every class of the surefire JVM.
 */
@ApplicationScoped
public class Fixtures {

  /** The private edge every state announces, with the values of qits-1976. */
  public static final String EDGE_APPLICATION = "qits-edge-private";

  public static final String EDGE_PUBLIC_KEY = key(1);

  /** A distinct, valid 44-character WireGuard public key per {@code n}. */
  public static String key(int n) {
    byte[] bytes = new byte[32];
    ByteBuffer.wrap(bytes).putInt(n);
    bytes[31] = 7;
    return Base64.getEncoder().encodeToString(bytes);
  }

  /** Deletes every row of the three tables. */
  @Transactional
  public void clear() {
    NaPeer.deleteAll();
    NaNode.deleteAll();
    NaPrivateEdge.deleteAll();
  }

  /** The private edge's announcement, as section 4's consumer will store it. */
  @Transactional
  public NaPrivateEdge edge() {
    NaPrivateEdge edge = new NaPrivateEdge();
    edge.application = EDGE_APPLICATION;
    edge.publicKey = EDGE_PUBLIC_KEY;
    edge.publicAddress = "46.224.171.33";
    edge.listenPort = 47812;
    edge.tunnelAddress = "100.96.0.1";
    edge.tunnelCidr = "100.96.0.0/24";
    edge.dnsAddress = "100.96.0.1";
    edge.mtu = 1380;
    edge.domain = "wohlben.eu";
    edge.announcedAt = Instant.parse("2026-11-10T08:00:00Z");
    edge.eventId = UUID.fromString("5d1c2a8e-3b7f-4c1d-9e2a-6f0b8c4d2e11");
    edge.persist();
    return edge;
  }

  /** An ACTIVE device peer, inserted directly (no event). */
  @Transactional
  public NaPeer device(UUID id, String name, String key, String tunnelIp, String owner) {
    NaPeer peer = new NaPeer();
    peer.id = id;
    peer.kind = PeerKind.DEVICE;
    peer.name = name;
    peer.publicKey = key;
    peer.tunnelIp = tunnelIp;
    peer.ownerUserId = owner;
    peer.status = PeerStatus.ACTIVE;
    peer.createdAt = Instant.parse("2026-11-10T09:00:00Z");
    peer.createdBy = owner;
    peer.persist();
    return peer;
  }

  /** A node that joined, with its ACTIVE NODE peer, inserted directly (no event). */
  @Transactional
  public NaPeer node(UUID targetId, String name, UUID peerId, String key, String tunnelIp) {
    NaNode node = new NaNode();
    node.targetId = targetId;
    node.name = name;
    node.createdAt = Instant.parse("2026-11-10T07:00:00Z");
    node.lastJoinedAt = Instant.parse("2026-11-10T07:00:00Z");
    node.persist();
    NaPeer peer = new NaPeer();
    peer.id = peerId;
    peer.kind = PeerKind.NODE;
    peer.name = name;
    peer.publicKey = key;
    peer.tunnelIp = tunnelIp;
    peer.targetId = targetId;
    peer.status = PeerStatus.ACTIVE;
    peer.createdAt = Instant.parse("2026-11-10T07:00:00Z");
    peer.createdBy = "dev-qits-system";
    peer.persist();
    return peer;
  }

  /** {@code count} ACTIVE device peers from the lowest address up, owned by {@code owner}. */
  @Transactional
  public void fillPool(int count, String owner) {
    for (int i = 0; i < count; i++) {
      device(UUID.randomUUID(), "fill-" + i, key(1000 + i), "100.96.0." + (10 + i), owner);
    }
  }

  // --- the fake bus ---------------------------------------------------------------------------

  /** Installs a bus that records instead of publishing, for the current test; returns its log. */
  public static List<QitsEvent> recordEvents() {
    List<QitsEvent> published = Collections.synchronizedList(new ArrayList<>());
    QuarkusMock.installMockForType(
        new QitsEventBus() {
          @Override
          public void publish(QitsEvent event) {
            published.add(event);
          }

          @Override
          public void publish(QitsEvent event, UUID parentEventId) {
            published.add(event);
          }
        },
        QitsEventBus.class);
    return published;
  }

  /** The recorded events of one type. */
  public static <E> List<E> of(List<QitsEvent> events, Class<E> type) {
    synchronized (events) {
      return events.stream().filter(type::isInstance).map(type::cast).toList();
    }
  }
}
