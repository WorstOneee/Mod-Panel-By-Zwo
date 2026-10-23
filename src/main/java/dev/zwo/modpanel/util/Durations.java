package dev.zwo.modpanel.util;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converte textos como "1h30m", "2d" ou "15 minutos" em duração. */
public final class Durations {

    private static final Pattern TOKEN = Pattern.compile("\\s*(\\d+)\\s*([a-zçêã]*)");

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    private static final Map<String, Long> UNITS = Map.ofEntries(
            Map.entry("s", SECOND), Map.entry("seg", SECOND), Map.entry("sec", SECOND), Map.entry("secs", SECOND),
            Map.entry("segundo", SECOND), Map.entry("segundos", SECOND),
            Map.entry("m", MINUTE), Map.entry("min", MINUTE), Map.entry("mins", MINUTE),
            Map.entry("minuto", MINUTE), Map.entry("minutos", MINUTE),
            Map.entry("h", HOUR), Map.entry("hr", HOUR), Map.entry("hora", HOUR), Map.entry("horas", HOUR),
            Map.entry("d", DAY), Map.entry("dia", DAY), Map.entry("dias", DAY),
            Map.entry("w", 7 * DAY), Map.entry("sem", 7 * DAY), Map.entry("semana", 7 * DAY), Map.entry("semanas", 7 * DAY),
            Map.entry("mo", 30 * DAY), Map.entry("mes", 30 * DAY), Map.entry("mês", 30 * DAY), Map.entry("meses", 30 * DAY),
            Map.entry("y", 365 * DAY), Map.entry("a", 365 * DAY), Map.entry("ano", 365 * DAY), Map.entry("anos", 365 * DAY)
    );

    private Durations() {
    }

    public static Optional<Duration> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String text = input.trim().toLowerCase(Locale.ROOT);
        if (text.chars().allMatch(Character::isDigit)) {
            text = text + "m";
        }

        Matcher matcher = TOKEN.matcher(text);
        int position = 0;
        long total = 0;
        boolean found = false;
        try {
            while (position < text.length()) {
                if (!matcher.find(position) || matcher.start() != position) {
                    return Optional.empty();
                }
                long amount = Long.parseLong(matcher.group(1));
                Long unit = UNITS.get(matcher.group(2));
                if (unit == null || amount <= 0) {
                    return Optional.empty();
                }
                total = Math.addExact(total, Math.multiplyExact(amount, unit));
                position = matcher.end();
                found = true;
            }
        } catch (ArithmeticException | NumberFormatException e) {
            return Optional.empty();
        }
        return found && total > 0 ? Optional.of(Duration.ofMillis(total)) : Optional.empty();
    }

    /** Duração em minutos inteiros (arredonda para cima). Retorna -1 se inválida. */
    public static int parseMinutes(String input) {
        return parse(input)
                .map(d -> (d.toMillis() + MINUTE - 1) / MINUTE)
                .filter(m -> m > 0 && m <= Integer.MAX_VALUE)
                .map(Long::intValue)
                .orElse(-1);
    }

    /** Formata um intervalo como "2d 3h 15m". */
    public static String format(long millis) {
        if (millis <= 0) {
            return "0m";
        }
        long minutes = (millis + MINUTE - 1) / MINUTE;
        long days = minutes / (24 * 60);
        minutes -= days * 24 * 60;
        long hours = minutes / 60;
        minutes -= hours * 60;

        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append("d ");
        }
        if (hours > 0) {
            out.append(hours).append("h ");
        }
        if (minutes > 0 || out.isEmpty()) {
            out.append(minutes).append('m');
        }
        return out.toString().trim();
    }
}
