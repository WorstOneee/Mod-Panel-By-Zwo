package dev.zwo.modpanel.util;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;

/** Validação de IPs literais, sem consultas de DNS. */
public final class Ips {

    private Ips() {
    }

    public static Optional<InetAddress> parseLiteral(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String value = input.trim();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        int scope = value.indexOf('%');
        if (scope > 0) {
            value = value.substring(0, scope);
        }

        if (!value.contains(":")) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) {
                return Optional.empty();
            }
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                    return Optional.empty();
                }
                if (Integer.parseInt(part) > 255) {
                    return Optional.empty();
                }
            }
        } else if (!value.matches("[0-9a-fA-F:.]+")) {
            return Optional.empty();
        }

        try {
            return Optional.of(InetAddress.getByName(value));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    public static String normalize(InetAddress address) {
        if (address == null) {
            return null;
        }
        String host = address.getHostAddress().toLowerCase(Locale.ROOT);
        int scope = host.indexOf('%');
        return scope > 0 ? host.substring(0, scope) : host;
    }

    public static String normalize(InetSocketAddress address) {
        return address == null ? null : normalize(address.getAddress());
    }

    /** IP normalizado ou null quando o texto não é um IP válido. */
    public static String normalize(String input) {
        return parseLiteral(input).map(Ips::normalize).orElse(null);
    }
}
