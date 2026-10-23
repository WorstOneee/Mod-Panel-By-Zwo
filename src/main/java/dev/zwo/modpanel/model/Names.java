package dev.zwo.modpanel.model;

import java.util.Locale;

public final class Names {

    private Names() {
    }

    /** Forma normalizada de um nome, usada nas buscas. */
    public static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
