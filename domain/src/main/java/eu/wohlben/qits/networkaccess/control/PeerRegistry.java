package eu.wohlben.qits.networkaccess.control;

import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.networkaccess.entity.NaNode;
import eu.wohlben.qits.networkaccess.entity.NaPeer;
import eu.wohlben.qits.networkaccess.entity.NaPrivateEdge;
import eu.wohlben.qits.networkaccess.entity.PeerKind;
import eu.wohlben.qits.networkaccess.entity.PeerStatus;
import eu.wohlben.qits.networkaccess.entity.RevokeReason;
import eu.wohlben.qits.networkaccess.events.PeerRegistered;
import eu.wohlben.qits.networkaccess.events.PeerRevoked;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The peers: registration, revocation and the reads. Every registration and every revocation
 * publishes its event ({@link PeerRegistered}, {@link PeerRevoked}) through {@link QitsEventBus} in
 * the same transaction as the row, after the flush that proves the row is valid — so an insert the
 * database refuses publishes nothing.
 *
 * <p><b>The allocation retry.</b> {@link PeerAddresses#next()} picks the lowest free address in the
 * insert's transaction; a concurrent registration that picked the same one loses on {@code
 * na_peer_active_tunnel_ip} and is retried, in a new transaction, up to {@value #ATTEMPTS} times.
 * The other partial unique indexes are refusals, not races: a duplicate key or name is a 409.
 *
 * <p>Writes are logged at INFO with the caller's subject and the peer's id, never the key.
 */
@ApplicationScoped
public class PeerRegistry {

  private static final Logger LOG = Logger.getLogger(PeerRegistry.class);

  static final int ATTEMPTS = 5;

  static final String DUPLICATE_KEY = "an active peer already holds this publicKey";
  static final String DUPLICATE_NAME = "you already have an active peer of this name";

  /** The partial unique indexes of V1, by what a violation of each means. */
  private static final Map<String, String> REFUSALS =
      Map.of(
          "na_peer_active_public_key", DUPLICATE_KEY,
          "na_peer_active_owner_name", DUPLICATE_NAME);

  private static final String ADDRESS_INDEX = "na_peer_active_tunnel_ip";

  @Inject PeerAddresses addresses;

  @Inject QitsEventBus bus;

  Clock clock = Clock.systemUTC();

  // --- reads ----------------------------------------------------------------------------------

  /**
   * Peers, newest first, filtered by whichever of {@code kind}, {@code status} and {@code owner} is
   * non-null.
   */
  @Transactional
  public List<NaPeer> list(PeerKind kind, PeerStatus status, String owner) {
    List<String> where = new ArrayList<>();
    Map<String, Object> params = new java.util.HashMap<>();
    if (kind != null) {
      where.add("kind = :kind");
      params.put("kind", kind);
    }
    if (status != null) {
      where.add("status = :status");
      params.put("status", status);
    }
    if (owner != null) {
      where.add("ownerUserId = :owner");
      params.put("owner", owner);
    }
    Sort newestFirst = Sort.descending("createdAt").and("id", Sort.Direction.Ascending);
    if (where.isEmpty()) {
      return NaPeer.findAll(newestFirst).list();
    }
    return NaPeer.<NaPeer>find(String.join(" and ", where), newestFirst, params).list();
  }

  /** One peer, or a 404. */
  @Transactional
  public NaPeer get(UUID id) {
    NaPeer peer = NaPeer.findById(id);
    if (peer == null) {
      throw NetworkAccessException.notFound("no peer " + id);
    }
    return peer;
  }

  /** Every node, by name. */
  @Transactional
  public List<NaNode> nodes() {
    return NaNode.findAll(Sort.ascending("name").and("targetId")).list();
  }

  /** One node, or a 404. */
  @Transactional
  public NaNode node(UUID targetId) {
    NaNode node = NaNode.findById(targetId);
    if (node == null) {
      throw NetworkAccessException.notFound("no node " + targetId);
    }
    return node;
  }

  /** The node's ACTIVE peer, if it has one. */
  @Transactional
  public Optional<NaPeer> activePeerOf(UUID targetId) {
    return NaPeer.<NaPeer>find(
            "targetId = ?1 and kind = ?2 and status = ?3",
            targetId,
            PeerKind.NODE,
            PeerStatus.ACTIVE)
        .firstResultOptional();
  }

  /**
   * The private edge configs and tunnel answers are rendered against: the most recent
   * announcement. Empty until an edge has announced itself.
   */
  @Transactional
  public Optional<NaPrivateEdge> privateEdge() {
    return NaPrivateEdge.<NaPrivateEdge>findAll(
            Sort.descending("announcedAt").and("application"))
        .firstResultOptional();
  }

  // --- writes ---------------------------------------------------------------------------------

  /**
   * Registers a DEVICE peer owned by {@code owner} (the caller's {@code sub}). 400 for a bad name or
   * key, 409 for a duplicate key, a duplicate name among the owner's ACTIVE peers, or a full pool.
   * Must be called outside a transaction: each attempt runs in its own.
   */
  public NaPeer registerDevice(String name, String publicKey, String owner, String caller) {
    String validName = PeerValidation.name(name);
    String validKey = PeerValidation.publicKey(publicKey);
    for (int attempt = 1; ; attempt++) {
      try {
        NaPeer peer =
            QuarkusTransaction.requiringNew()
                .call(() -> insertDevice(validName, validKey, owner, caller));
        LOG.infof("%s registered the device peer %s as %s", caller, peer.id, peer.tunnelIp);
        return peer;
      } catch (RuntimeException e) {
        String constraint = violatedConstraint(e);
        if (constraint == null) {
          throw e;
        }
        if (REFUSALS.containsKey(constraint)) {
          throw NetworkAccessException.conflict(REFUSALS.get(constraint));
        }
        if (!ADDRESS_INDEX.equals(constraint) || attempt >= ATTEMPTS) {
          throw e;
        }
        LOG.debugf("tunnel address race on attempt %d; retrying", attempt);
      }
    }
  }

  private NaPeer insertDevice(String name, String publicKey, String owner, String caller) {
    if (NaPeer.count("publicKey = ?1 and status = ?2", publicKey, PeerStatus.ACTIVE) > 0) {
      throw NetworkAccessException.conflict(DUPLICATE_KEY);
    }
    if (NaPeer.count(
            "ownerUserId = ?1 and name = ?2 and status = ?3", owner, name, PeerStatus.ACTIVE)
        > 0) {
      throw NetworkAccessException.conflict(DUPLICATE_NAME);
    }
    NaPeer peer = new NaPeer();
    peer.id = UUID.randomUUID();
    peer.kind = PeerKind.DEVICE;
    peer.name = name;
    peer.publicKey = publicKey;
    peer.tunnelIp = addresses.next();
    peer.ownerUserId = owner;
    peer.status = PeerStatus.ACTIVE;
    peer.createdAt = now();
    peer.createdBy = caller;
    peer.persist();
    NaPeer.flush();
    bus.publish(registered(peer));
    return peer;
  }

  /**
   * Revokes a peer, by hand ({@code REVOKED}) or for one of the event-driven reasons. Idempotent: a
   * peer already REVOKED is answered unchanged and publishes nothing.
   */
  @Transactional
  public NaPeer revoke(UUID id, String caller, RevokeReason reason) {
    NaPeer peer = get(id);
    if (peer.status == PeerStatus.REVOKED) {
      return peer;
    }
    peer.status = PeerStatus.REVOKED;
    peer.revokedAt = now();
    peer.revokedBy = caller;
    peer.revokeReason = reason;
    NaPeer.flush();
    bus.publish(revoked(peer));
    LOG.infof("%s revoked the peer %s (%s)", caller, peer.id, reason);
    return peer;
  }

  // --- the events -----------------------------------------------------------------------------

  private PeerRegistered registered(NaPeer peer) {
    return new PeerRegistered(
        UUID.randomUUID(),
        peer.id,
        peer.kind.name(),
        peer.name,
        peer.publicKey,
        peer.tunnelIp,
        peer.kind == PeerKind.DEVICE ? peer.ownerUserId : null,
        peer.kind == PeerKind.NODE ? peer.targetId : null,
        peer.createdAt);
  }

  private static PeerRevoked revoked(NaPeer peer) {
    return new PeerRevoked(
        UUID.randomUUID(),
        peer.id,
        peer.kind.name(),
        peer.name,
        peer.publicKey,
        peer.tunnelIp,
        peer.revokeReason.name(),
        peer.revokedAt);
  }

  private Instant now() {
    // Microseconds, which is what timestamptz keeps: the answer and a later read agree.
    return Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  /** The unique index a failure violated, read off the postgres error; null for anything else. */
  static String violatedConstraint(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof org.hibernate.exception.ConstraintViolationException hibernate
          && hibernate.getConstraintName() != null) {
        return hibernate.getConstraintName();
      }
      if (t instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
        String message = String.valueOf(sql.getMessage());
        for (String index : List.of(ADDRESS_INDEX, "na_peer_active_public_key", "na_peer_active_owner_name", "na_peer_active_node")) {
          if (message.contains(index)) {
            return index;
          }
        }
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return null;
  }
}
