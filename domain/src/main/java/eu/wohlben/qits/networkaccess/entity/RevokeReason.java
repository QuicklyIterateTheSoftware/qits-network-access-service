package eu.wohlben.qits.networkaccess.entity;

/**
 * Why a peer stopped being ACTIVE: by hand ({@code REVOKED}), because its owner was deleted at
 * qits-idp, because its node's target was deleted at qits-system, or because a node re-joined with
 * a new key ({@code REPLACED}).
 */
public enum RevokeReason {
  REVOKED,
  USER_DELETED,
  NODE_DELETED,
  REPLACED
}
