package eu.wohlben.qits.networkaccess;

import eu.wohlben.qits.eventstream.contracts.EventstreamPact;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>What qits-eventstream asks qits-events on this service's behalf</b>: the outbox publish of
 * PeerRegistered and PeerRevoked ({@code control.PeerRegistry}), pacted against the library
 * version this repository pins and committed as {@code
 * pacts/qits-network-access-service_qits-eventstream-javalib_qits-events-service.json}. It consumes
 * no event yet — the durable listeners of the design's section 4 join {@code listeners(...)} with
 * their task — and the guard fails naming any listener or {@code QitsEventBus} user in either
 * module's classes left out here.
 */
class EventstreamPactTest {

  static final EventstreamPact PACT =
      EventstreamPact.forConsumer("qits-network-access-service")
          .publishes(true)
          .guard(Path.of("target/classes"), Path.of("../domain/target/classes"));

  @TestFactory
  Stream<DynamicTest> rows() {
    return PACT.tests();
  }

  @Test
  void pactFile() {
    PACT.compareOrWritePactFile();
  }
}
