package eu.wohlben.qits.networkaccess.api;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Who is calling, as the owner of a device peer and the {@code created_by} / {@code revoked_by} of
 * a write: the token's {@code sub}. For a person that is the idp user id (a UUID) — the id
 * {@code UserDeleted} names — and for a desk or a service it is that client's id.
 *
 * <p>The {@code sub} rather than the principal name on purpose: quarkus-oidc's principal prefers
 * {@code upn} and {@code preferred_username}, which a person can change at qits-idp. An identity
 * with no JWT behind it (a suite's {@code @TestSecurity}) answers its principal name.
 */
final class Callers {

  private Callers() {}

  static String subject(SecurityIdentity identity) {
    if (identity.getPrincipal() instanceof JsonWebToken jwt
        && jwt.getSubject() != null
        && !jwt.getSubject().isBlank()) {
      return jwt.getSubject();
    }
    return identity.getPrincipal().getName();
  }
}
