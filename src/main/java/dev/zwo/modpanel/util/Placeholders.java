package dev.zwo.modpanel.util;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Placeholders {

    private Placeholders() {
    }

    /** Monta um mapa a partir de pares chave/valor: of("player", nome, "count", 3). */
    public static Map<String, String> of(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Pares de placeholder incompletos");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1] == null ? "" : String.valueOf(pairs[i + 1]));
        }
        return map;
    }
}
