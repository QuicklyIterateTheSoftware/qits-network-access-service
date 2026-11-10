package eu.wohlben.qits.networkaccess.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.networkaccess.system.RegistrationCheck;
import eu.wohlben.qits.networkaccess.system.SystemBearer;
import eu.wohlben.qits.networkaccess.system.SystemTargets;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>What this service asks qits-system-service, and why</b> (epic qits-1977): the node join's
 * registration check, {@code checkTargetRegistration} — {@code POST
 * /system/api/targets/{targetId}/registration-check} {@code {subject}} with this service's own
 * {@code qits:system} bearer — answered 200 {@code {targetId, targetName}} in qits-system's state
 * {@value #STATE}. Driven through the real {@link RegistrationCheck} over the real generated {@link
 * SystemTargets} client, against a pact mock server.
 *
 * <p><b>THE RECORDING IS A FIXTURE COPY FOR NOW.</b> qits-system recorded the state on epic
 * qits-1977 (qits-2022), but no published {@code eu.wohlben.qits:qits-system-golden-masters} carries
 * it yet (2026.1108.50219 is the newest). So the state's index entry and answer are copied
 * verbatim from qits-system-service's {@code golden-masters/} into {@code
 * src/test/resources/contract-fixtures/qits-system-golden-masters/}. Once a version with the state
 * is published: pin it in the root pom as a test dependency, switch {@link #SYSTEM} to {@code
 * GoldenMasters.of}, delete the fixture, and the pact must come out byte-identical.
 *
 * <p>The pact is {@code pacts/qits-network-access-service_qits-system-service.json}; {@code
 * -Dgolden.update=true} rewrites it. {@code @QuarkusTest} because the reactive REST client is built
 * only inside a running application.
 */
@QuarkusTest
class SystemPactTest {

  static final GoldenMasters SYSTEM =
      GoldenMasters.at(
          "contract-fixtures/qits-system-golden-masters/", "qits-system-service", "qits-system");

  static final String STATE =
      "target 00000000-0000-4000-8000-000000000001 has registration subject"
          + " contract-registration-subject";

  /** The check, caused by a registration token's {@code joinNode}. */
  static final GoldenInteraction CHECK =
      GoldenInteraction.of(Trigger.operation("joinNode"), STATE, "checkTargetRegistration")
          .header("Authorization", "{authorization}")
          .consumes("targetId", "targetName");

  static final ConsumerPact PACT = ConsumerPact.of(IdpPactTest.CONSUMER, SYSTEM, CHECK);

  @Test
  void theJoinsCheckReadsTheTargetsNameForItsToken() {
    PACT.run(
        CHECK,
        (url, recorded) -> {
          Map<String, String> p = recorded.params();
          SystemTargets client =
              QuarkusRestClientBuilder.newBuilder().baseUri(URI.create(url)).build(SystemTargets.class);
          RegistrationCheck check = RegistrationCheck.over(client, bearer(p.get("authorization")));
          assertEquals(
              SYSTEM.json(STATE, CHECK.operationId()).path("targetName").asText(),
              check.targetName(UUID.fromString(p.get("targetId")), p.get("subject")));
        });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.compareOrWritePactFile();
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    PACT.assertEveryInteractionCarriesBothReferences();
    assertEquals(1, PACT.recorded().size(), "the row is recorded: " + PACT.unrecorded());
  }

  private static SystemBearer bearer(String authorization) {
    return new SystemBearer() {
      @Override
      public Optional<String> authorization() {
        return Optional.of(authorization);
      }
    };
  }
}
