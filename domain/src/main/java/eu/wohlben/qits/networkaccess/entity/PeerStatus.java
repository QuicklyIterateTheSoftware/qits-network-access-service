package eu.wohlben.qits.networkaccess.entity;

/** A peer is ACTIVE until it is revoked; a revoked row is kept as history and never reactivated. */
public enum PeerStatus {
  ACTIVE,
  REVOKED
}
