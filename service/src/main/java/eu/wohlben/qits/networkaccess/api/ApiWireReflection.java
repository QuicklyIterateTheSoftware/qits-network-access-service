package eu.wohlben.qits.networkaccess.api;

import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.networkaccess.events.PeerRegistered;
import eu.wohlben.qits.networkaccess.events.PeerRevoked;
import eu.wohlben.qits.networkaccess.bus.PrivateEdgeAnnouncedListener;
import eu.wohlben.qits.networkaccess.bus.TargetDeletedListener;
import eu.wohlben.qits.networkaccess.bus.UserDeletedListener;
import eu.wohlben.qits.networkaccess.system.SystemTargets;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection registration for every type Jackson touches on this API, for the two
 * events the bus serializes, for the three payloads the consumers decode and for the qits-system
 * client's body and answer. Several of them ride in {@code Response.entity(...)}, which hides the
 * type from the build-time analysis: in the native binary that is a 500 behind a green JVM suite.
 *
 * <p><b>A new served type joins this list in the commit that adds it</b>; ApiWireReflectionTest
 * fails naming any nested type of {@link NetworkAccessWire}, any consumer's nested payload record
 * and any record of {@link SystemTargets} left out.
 */
@RegisterForReflection(
    targets = {
      NetworkAccessWire.CreatePeerRequest.class,
      NetworkAccessWire.JoinNodeRequest.class,
      NetworkAccessWire.PeerDto.class,
      NetworkAccessWire.NodeDto.class,
      NetworkAccessWire.EdgeDto.class,
      NetworkAccessWire.TunnelDto.class,
      NetworkAccessWire.PrivateEdgeDto.class,
      NetworkAccessWire.CompanionDto.class,
      NetworkAccessWire.ErrorBody.class,
      PeerRegistered.class,
      PeerRevoked.class,
      // qits-eventstream serializes with its own ObjectMapper, so nothing registers its PUT body or
      // the frames it reads for us: without these the native image publishes nothing, silently
      // (qits-1977, measured on the private edge, which lacked them).
      EventEnvelope.class,
      EventFrame.class,
      // The consumers' private wire DTOs (bus/), which CanonicalJson binds by reflection.
      PrivateEdgeAnnouncedListener.PrivateEdgeAnnouncedPayload.class,
      UserDeletedListener.UserDeletedPayload.class,
      TargetDeletedListener.TargetDeletedPayload.class,
      // The qits-system REST client's body and answer.
      SystemTargets.RegistrationQuestion.class,
      SystemTargets.RegistrationAnswer.class
    },
    classNames = {
      "eu.wohlben.qits.eventstream.control.EventPage",
      "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin"
    })
public final class ApiWireReflection {

  private ApiWireReflection() {}
}
