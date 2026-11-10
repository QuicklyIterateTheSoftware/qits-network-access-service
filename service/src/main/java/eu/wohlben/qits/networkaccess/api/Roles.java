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

  private Roles() {}
}
