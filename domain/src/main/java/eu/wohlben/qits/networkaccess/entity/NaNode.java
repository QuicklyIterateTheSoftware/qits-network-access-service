package eu.wohlben.qits.networkaccess.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A qits-system target that joined the tunnel. The id is the target's own: node identity stays
 * with qits-system, and this row only remembers the join.
 *
 * <p><b>Uncaused.</b> A node is written by the join door (a caller, not an event); the one
 * event-driven change, {@code deleted_at} on {@code TargetDeleted}, is recorded on the peer it
 * revokes as {@code revoke_reason = NODE_DELETED}, which is the trace that matters.
 */
@Entity
@Table(name = "na_node")
@Uncaused
public class NaNode extends PanacheEntityBase {

  @Id
  @Column(name = "target_id")
  public UUID targetId;

  @Column(nullable = false, columnDefinition = "text")
  public String name;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "last_joined_at")
  public Instant lastJoinedAt;

  @Column(name = "deleted_at")
  public Instant deletedAt;
}
