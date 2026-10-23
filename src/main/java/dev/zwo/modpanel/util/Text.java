package dev.zwo.modpanel.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;
import java.util.Map;

public final class Text {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private Text() {
    }

    public static Component component(String legacy) {
        return SERIALIZER.deserialize(legacy == null ? "" : legacy);
    }

    /** Texto para nome/lore de item, sem o itálico padrão do jogo. */
    public static Component item(String legacy) {
        return component(legacy).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> lore(List<String> lines) {
        return lines.stream().map(Text::item).toList();
    }

    public static String replace(String text, Map<String, String> values) {
        if (text == null) {
            return "";
        }
        String out = text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            out = out.replace("{" + entry.getKey() + "}", value);
        }
        return out;
    }
}
