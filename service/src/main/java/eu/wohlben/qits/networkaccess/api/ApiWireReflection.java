package eu.wohlben.qits.networkaccess.api;

import eu.wohlben.qits.networkaccess.events.PeerRegistered;
import eu.wohlben.qits.networkaccess.events.PeerRevoked;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection registration for every type Jackson touches on this API, and for the two
 * events the bus serializes. Several of them ride in {@code Response.entity(...)}, which hides the
 * type from the build-time analysis: in the native binary that is a 500 behind a green JVM suite.
 *
 * <p><b>A new served type joins this list in the commit that adds it</b>; ApiWireReflectionTest
 * fails naming any nested type of {@link NetworkAccessWire} left out.
 */
@RegisterForReflection(
    targets = {
      NetworkAccessWire.CreatePeerRequest.class,
      NetworkAccessWire.PeerDto.class,
      NetworkAccessWire.NodeDto.class,
      NetworkAccessWire.EdgeDto.class,
      NetworkAccessWire.TunnelDto.class,
      NetworkAccessWire.PrivateEdgeDto.class,
      NetworkAccessWire.CompanionDto.class,
      NetworkAccessWire.ErrorBody.class,
      PeerRegistered.class,
      PeerRevoked.class
    })
public final class ApiWireReflection {

  private ApiWireReflection() {}
}
