package eu.wohlben.qits.networkaccess.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * One WireGuard peer of the private edge: a person's device or a joined node. It carries the
 * public key only; no private key ever reaches this service.
 *
 * <p>{@code tunnel_ip} is a postgres {@code inet}, held here as its text form: the column
 * transformer casts on write and reads the bare host back ({@code host()}, without the {@code /32}).
 *
 * <p><b>Uncaused.</b> The schema carries no causation column on purpose: a registration is a
 * caller's, and an event-driven revocation names its cause in {@code revoke_reason} and {@code
 * revoked_by} (USER_DELETED by qits-idp, NODE_DELETED by qits-system), which is what an operator
 * reads.
 */
@Entity
@Table(name = "na_peer")
@Uncaused
public class NaPeer extends PanacheEntityBase {

  @Id public UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "text")
  public PeerKind kind;

  @Column(nullable = false, columnDefinition = "text")
  public String name;

  @Column(name = "public_key", nullable = false, columnDefinition = "text")
  public String publicKey;

  @Column(name = "tunnel_ip", nullable = false, columnDefinition = "inet")
  @ColumnTransformer(read = "host(tunnel_ip)", write = "?::inet")
  public String tunnelIp;

  @Column(name = "owner_user_id", columnDefinition = "text")
  public String ownerUserId;

  @Column(name = "target_id")
  public UUID targetId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "text")
  public PeerStatus status;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "created_by", nullable = false, columnDefinition = "text")
  public String createdBy;

  @Column(name = "revoked_at")
  public Instant revokedAt;

  @Column(name = "revoked_by", columnDefinition = "text")
  public String revokedBy;

  @Enumerated(EnumType.STRING)
  @Column(name = "revoke_reason", columnDefinition = "text")
  public RevokeReason revokeReason;
}
