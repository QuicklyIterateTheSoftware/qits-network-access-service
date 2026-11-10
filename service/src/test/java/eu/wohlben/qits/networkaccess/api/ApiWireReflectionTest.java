package eu.wohlben.qits.networkaccess.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.networkaccess.events.PeerRegistered;
import eu.wohlben.qits.networkaccess.events.PeerRevoked;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiWireReflection} is complete: every nested type of {@link NetworkAccessWire}, and both
 * events. On a JVM everything binds regardless; the failure this guards is a 500 in the native
 * binary, so the only way to catch it early is the commit that forgets the list.
 */
class ApiWireReflectionTest {

  @Test
  void everyWireTypeIsRegistered() {
    RegisterForReflection registration =
        ApiWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class");
    Set<Class<?>> registered = Set.of(registration.targets());
    Class<?>[] nested = NetworkAccessWire.class.getDeclaredClasses();
    assertTrue(nested.length >= 8, "the sweep found only " + nested.length + " wire types");
    for (Class<?> type : nested) {
      assertTrue(
          registered.contains(type),
          type.getSimpleName() + " is on the wire and not in ApiWireReflection");
    }
    assertTrue(registered.contains(PeerRegistered.class));
    assertTrue(registered.contains(PeerRevoked.class));
  }
}
