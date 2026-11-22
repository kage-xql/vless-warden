package com.example.vlesswarden.vless;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Нормализует IP из Xray.
 *
 * <p>IPv4 остаётся как есть. IPv6 приводится к сети /64: один телефон с
 * privacy-адресами не должен выглядеть как десяток разных устройств.
 * IPv4-mapped IPv6 (`::ffff:1.2.3.4`) превращается обратно в IPv4.</p>
 */
public final class IpAddresses {
    private IpAddresses() {
    }

    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("IP address is missing");
        String value = raw.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        int zone = value.indexOf('%');
        if (zone >= 0) value = value.substring(0, zone);
        if (value.isEmpty()) throw new IllegalArgumentException("IP address is blank");

        InetAddress address = parse(value);
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return ipv4(bytes, 0);
        if (bytes.length == 16) {
            if (isV4Mapped(bytes)) return ipv4(bytes, 12);
            return String.format("%x:%x:%x:%x::/64",
                    group(bytes, 0), group(bytes, 2), group(bytes, 4), group(bytes, 6));
        }
        throw new IllegalArgumentException("Unsupported IP address: " + raw);
    }

    private static InetAddress parse(String value) {
        if (value.indexOf(':') < 0) {
            if (!value.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                throw new IllegalArgumentException("Invalid IP address: " + value);
            }
        } else if (!value.matches("[0-9a-fA-F:.]+")) {
            throw new IllegalArgumentException("Invalid IP address: " + value);
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP address: " + value, e);
        }
    }

    private static boolean isV4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return false;
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    private static int group(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static String ipv4(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) + "." + (bytes[offset + 1] & 0xff) + "."
                + (bytes[offset + 2] & 0xff) + "." + (bytes[offset + 3] & 0xff);
    }
}
