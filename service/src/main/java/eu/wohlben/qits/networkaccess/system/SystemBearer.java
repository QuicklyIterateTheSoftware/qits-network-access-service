package eu.wohlben.qits.networkaccess.system;

import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The bearer this service presents to qits-system: the named oidc client {@code qits}, this
 * process's own qits-idp service client by client credentials (the {@code idp:client} resource,
 * {@code qits:system}, audience {@code qits-platform}) — the one identity the eventstream library
 * and the telemetry exporters already spend. Empty when the client is off (a clone-alone suite),
 * and then the call goes out without one and qits-system answers 401.
 */
@ApplicationScoped
public class SystemBearer {

  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled")
  boolean enabled;

  @Inject @NamedOidcClient("qits") OidcClient client;

  private final TokensHelper tokens = new TokensHelper();

  public Optional<String> authorization() {
    if (!enabled) {
      return Optional.empty();
    }
    String token = tokens.getTokens(client).await().atMost(TOKEN_TIMEOUT).getAccessToken();
    return Optional.ofNullable(token).filter(t -> !t.isBlank()).map(t -> "Bearer " + t);
  }
}
