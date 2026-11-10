package eu.wohlben.qits.networkaccess.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventsPublisher;
import eu.wohlben.qits.eventstream.control.Outbox;
import eu.wohlben.qits.eventstream.persistence.ConsumedEvents;
import eu.wohlben.qits.eventstream.persistence.OutboxRepository;
import eu.wohlben.qits.networkaccess.entity.NaNode;
import eu.wohlben.qits.networkaccess.entity.NaPeer;
import eu.wohlben.qits.networkaccess.entity.NaPrivateEdge;
import eu.wohlben.qits.networkaccess.support.Fixtures;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The three consumers inside the library's claim transaction</b>, as production runs them: the
 * qits-eventstream {@code DurableFunnel} claims the event in {@code consumed_event} — a write on the
 * {@code eventstream} datasource — and calls {@code onFrame} inside that same JTA transaction. This
 * suite's two datasources are two DISTINCT databases (testdb/EmbeddedPgConfigSource, as a
 * deployment's {@code db} and {@code eventstream} resources are), both non-XA, so a handler whose
 * {@code networkaccess} write joins the claim's transaction is a second one-phase resource in it,
 * which Narayana refuses ("Exception in association of connection to existing transaction"); the
 * funnel then rolls the claim back and the event stays owed forever (live on 2026.1110.191501).
 *
 * <p>{@link ConsumersTest} calls {@code onFrame} with no transaction around it, which is why it
 * could not see this. Here the funnel's step is reproduced verbatim — {@code requiringNew}, {@code
 * claim}, {@code onFrame} — because the real funnel is dark in {@code %test} ({@code
 * qits.eventstream.enabled=false} answers SKIPPED before it claims).
 */
@QuarkusTest
class ClaimTransactionTest {

  static final String ENVIRONMENT = "dev";

  @Inject Fixtures fixtures;

  @Inject ConsumedEvents consumed;

  @Inject Outbox outbox;

  @Inject OutboxRepository outboxRows;

  @Inject PrivateEdgeAnnouncedListener edges;

  @Inject UserDeletedListener users;

  @Inject TargetDeletedListener targets;

  @Inject
  @DataSource("networkaccess")
  AgroalDataSource networkaccess;

  @Inject
  @DataSource("eventstream")
  AgroalDataSource eventstream;

  @BeforeEach
  void clean() {
    fixtures.clear();
    Fixtures.recordEvents();
  }

  @Test
  void theTwoDatasourcesAreDistinctDatabases() throws Exception {
    assertNotEquals(database(networkaccess), database(eventstream));
  }

  @Test
  void anAnnouncementCommitsWithItsClaim() throws Exception {
    EventFrame frame =
        frame(
            "PrivateEdgeAnnounced",
            new JsonObject()
                .put("application", "qits-edge-private")
                .put("publicKey", Fixtures.EDGE_PUBLIC_KEY)
                .put("publicAddress", "46.224.171.33")
                .put("listenPort", 47812)
                .put("tunnelAddress", "100.96.0.1")
                .put("tunnelCidr", "100.96.0.0/24")
                .put("dnsAddress", "100.96.0.1")
                .put("mtu", 1380)
                .put("domain", "wohlben.eu"));
    funnel(edges, frame);
    assertNotNull(
        QuarkusTransaction.requiringNew().call(() -> NaPrivateEdge.findById("qits-edge-private")));
    assertTrue(claimed(edges, frame));
  }

  @Test
  void aUserDeletionRevokesAndCommitsWithItsClaim() throws Exception {
    UUID laptop = UUID.randomUUID();
    String owner = "7c0d9e8a-1b2c-4d3e-8f4a-5b6c7d8e9f00";
    fixtures.device(laptop, "laptop", Fixtures.key(2), "100.96.0.10", owner);
    EventFrame frame = frame("UserDeleted", new JsonObject().put("userId", owner).put("username", "a"));
    funnel(users, frame);
    assertEquals("REVOKED", QuarkusTransaction.requiringNew().call(() -> NaPeer.<NaPeer>findById(laptop)).status.name());
    assertTrue(claimed(users, frame));
  }

  @Test
  void aTargetDeletionRevokesAndCommitsWithItsClaim() throws Exception {
    UUID target = UUID.randomUUID();
    UUID peer = UUID.randomUUID();
    fixtures.node(target, "lab", peer, Fixtures.key(4), "100.96.0.11");
    EventFrame frame = frame("TargetDeleted", new JsonObject().put("targetId", target.toString()));
    funnel(targets, frame);
    assertEquals("REVOKED", QuarkusTransaction.requiringNew().call(() -> NaPeer.<NaPeer>findById(peer)).status.name());
    assertNotNull(QuarkusTransaction.requiringNew().call(() -> NaNode.<NaNode>findById(target)).deletedAt);
    assertTrue(claimed(targets, frame));
  }

  /**
   * The write doors' half (POST /peers, revoke, join, and the revocations above): a {@code
   * networkaccess} write and, in the same transaction, the bus's outbox row on {@code eventstream}.
   * {@code QitsEventBus.publish} tries an inline PUT (no database) and only then calls {@code
   * Outbox.enqueue}, which runs {@code requiringNew} and so never enlists the outbox's connection
   * in the caller's transaction. Driven here through the library's real {@link Outbox}, because the
   * suite's bus is dark and would return before reaching it.
   */
  @Test
  void aWriteThatEnqueuesItsEventCommitsBothRows() {
    UUID peer = UUID.randomUUID();
    String eventId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              fixtures.device(peer, "laptop", Fixtures.key(2), "100.96.0.10", "someone");
              outbox.enqueue(
                  eventId,
                  new EventEnvelope(
                      "PeerRegistered", Instant.now(), "{}", null, null, ENVIRONMENT, null),
                  new EventsPublisher.Delivery(EventsPublisher.Outcome.UNREACHABLE, "a test"));
            });
    assertNotNull(QuarkusTransaction.requiringNew().call(() -> NaPeer.findById(peer)));
    assertTrue(
        QuarkusTransaction.requiringNew().call(() -> outboxRows.findByIdOptional(eventId).isPresent()),
        "the outbox row committed on its own");
    QuarkusTransaction.requiringNew().run(() -> outboxRows.deleteById(eventId));
  }

  /** DurableFunnel's step, verbatim: one new transaction, the claim, then the handler. */
  private void funnel(QitsDurableEventListener listener, EventFrame frame) {
    assertTrue(listener.selects(frame));
    boolean handled =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  if (!consumed.claim(listener.consumerId(), frame.id(), Instant.now())) {
                    return false;
                  }
                  listener.onFrame(frame);
                  return true;
                });
    assertTrue(handled, "the event was claimed by nobody else");
  }

  private boolean claimed(QitsDurableEventListener listener, EventFrame frame) {
    return QuarkusTransaction.requiringNew()
        .call(() -> consumed.isHandled(listener.consumerId(), frame.id()));
  }

  private static String database(AgroalDataSource source) throws Exception {
    try (Connection c = source.getConnection();
        ResultSet r = c.createStatement().executeQuery("select current_database()")) {
      r.next();
      return r.getString(1);
    }
  }

  private static EventFrame frame(String name, JsonObject payload) {
    return new EventFrame(
        UUID.randomUUID().toString(), name, Instant.now(), payload.encode(), null, null, ENVIRONMENT);
  }
}
