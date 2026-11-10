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
 * What a private edge announced about itself ({@code PrivateEdgeAnnounced}): its public key and
 * endpoint, the tunnel network and its DNS address. One row per edge application, replaced only by
 * a newer announcement. Every rendered config and tunnel answer reads it.
 *
 * <p><b>Uncaused</b>, although an event writes it: the announcement's own {@code event_id} is a
 * column of the row, which is the causation record in full.
 */
@Entity
@Table(name = "na_private_edge")
@Uncaused
public class NaPrivateEdge extends PanacheEntityBase {

  @Id
  @Column(columnDefinition = "text")
  public String application;

  @Column(name = "public_key", nullable = false, columnDefinition = "text")
  public String publicKey;

  @Column(name = "public_address", nullable = false, columnDefinition = "text")
  public String publicAddress;

  @Column(name = "listen_port", nullable = false)
  public int listenPort;

  @Column(name = "tunnel_address", nullable = false, columnDefinition = "text")
  public String tunnelAddress;

  @Column(name = "tunnel_cidr", nullable = false, columnDefinition = "text")
  public String tunnelCidr;

  @Column(name = "dns_address", nullable = false, columnDefinition = "text")
  public String dnsAddress;

  @Column(nullable = false)
  public int mtu;

  @Column(nullable = false, columnDefinition = "text")
  public String domain;

  @Column(name = "announced_at", nullable = false)
  public Instant announcedAt;

  @Column(name = "event_id", nullable = false)
  public UUID eventId;
}
