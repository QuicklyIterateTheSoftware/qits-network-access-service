package eu.wohlben.qits.networkaccess.api;

/**
 * The roles the design page names, as constants the {@code @RolesAllowed} lists spell out.
 *
 * <ul>
 *   <li><b>Reads</b> (peers, nodes, configs, tunnel parameters, the private edge, the companion
 *       version): {@code qits:admin}, {@code qits:admin-agent}, {@code qits:desk-admin}, {@code
 *       qits:desk}, {@code qits:agent} and {@code qits:system}. A config carries no secret — no
 *       private key ever reaches this service — so it is a read.
 *   <li><b>Writes</b> (add a device, revoke): the same without {@code qits:agent}, the set
 *       qits-system's {@code TargetController} uses for create/patch/delete. {@code qits:agent} is
 *       refused 403.
 *   <li><b>The node join</b> ({@code POST /nodes/{targetId}/join}): the write set, which skips the
 *       registration check (an admin's repair), and {@code qits:target-runner-registration}, which
 *       is checked against qits-system. The helper binary ({@code GET /nodes/qits-node-tunnel}):
 *       the read set and {@code qits:target-runner-registration}.
 * </ul>
 *
 * <p>Each resource carries the READ set at class level, and each write method carries the WRITE
 * set, which REPLACES the class-level one (JAX-RS takes the method's annotation, not the union).
 * DeskAdminParityRules holds over both: wherever {@code qits:admin-agent} is admitted, so is {@code
 * qits:desk-admin}.
 */
public final class Roles {

  public static final String ADMIN = "qits:admin";
  public static final String ADMIN_AGENT = "qits:admin-agent";
  public static final String DESK_ADMIN = "qits:desk-admin";
  public static final String DESK = "qits:desk";
  public static final String AGENT = "qits:agent";
  public static final String SYSTEM = "qits:system";

  /**
   * A node's registration token (qits-system mints it for one target's install line): admitted to
   * the node join, which then asks qits-system whether the token is that target's, and to the
   * helper binary the install line downloads. Nothing else.
   */
  public static final String TARGET_RUNNER_REGISTRATION = "qits:target-runner-registration";

  /** The write set, for code that asks whether a caller holds it. */
  public static final java.util.Set<String> WRITE =
      java.util.Set.of(ADMIN, ADMIN_AGENT, DESK_ADMIN, DESK, SYSTEM);

  private Roles() {}
}
