package eu.wohlben.qits.networkaccess.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tunnel's IPv4 network and the part of it peers are allocated from: from host number {@code
 * firstHost} up to the last host below the broadcast address. For {@code 100.96.0.0/24} and {@code
 * 10} that is {@code 100.96.0.10} to {@code 100.96.0.254}, 245 addresses.
 *
 * <p>Pure arithmetic, no I/O; {@link PeerAddresses} reads the configuration into one of these.
 */
public record TunnelNetwork(int network, int prefix, int firstHost) {

  public TunnelNetwork {
    if (prefix < 1 || prefix > 30) {
      throw new IllegalArgumentException("a tunnel prefix must be /1 to /30, not /" + prefix);
    }
    int hosts = (1 << (32 - prefix)) - 2;
    if (firstHost < 1 || firstHost > hosts) {
      throw new IllegalArgumentException(
          "first-host " + firstHost + " is outside the " + hosts + " hosts of a /" + prefix);
    }
    if ((network & ~mask(prefix)) != 0) {
      throw new IllegalArgumentException(
          format(network) + "/" + prefix + " is not a network address");
    }
  }

  /** Parses {@code a.b.c.d/n}. */
  public static TunnelNetwork parse(String cidr, int firstHost) {
    String[] parts = cidr.trim().split("/", 2);
    if (parts.length != 2) {
      throw new IllegalArgumentException("not a CIDR: " + cidr);
    }
    return new TunnelNetwork(parseAddress(parts[0]), Integer.parseInt(parts[1]), firstHost);
  }

  /** Every allocatable address, lowest first. */
  public List<String> allocatable() {
    int last = (1 << (32 - prefix)) - 2;
    List<String> out = new ArrayList<>(last - firstHost + 1);
    for (int host = firstHost; host <= last; host++) {
      out.add(format(network + host));
    }
    return out;
  }

  /** The CIDR this network was parsed from, normalised. */
  public String cidr() {
    return format(network) + "/" + prefix;
  }

  /** The dotted-quad form of an IPv4 address. */
  public static String format(int address) {
    return String.format(
        Locale.ROOT,
        "%d.%d.%d.%d",
        (address >>> 24) & 0xff,
        (address >>> 16) & 0xff,
        (address >>> 8) & 0xff,
        address & 0xff);
  }

  /** Parses a dotted-quad IPv4 address. */
  public static int parseAddress(String text) {
    String[] octets = text.trim().split("\\.", -1);
    if (octets.length != 4) {
      throw new IllegalArgumentException("not an IPv4 address: " + text);
    }
    int address = 0;
    for (String octet : octets) {
      int value = Integer.parseInt(octet);
      if (value < 0 || value > 255) {
        throw new IllegalArgumentException("not an IPv4 address: " + text);
      }
      address = (address << 8) | value;
    }
    return address;
  }

  private static int mask(int prefix) {
    return prefix == 0 ? 0 : -1 << (32 - prefix);
  }
}
