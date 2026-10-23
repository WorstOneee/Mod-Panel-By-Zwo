package dev.zwo.modpanel.storage;

/** Nomes das tabelas já com o prefixo configurado. */
public record Tables(String prefix, String meta, String identities, String names, String ips,
                     String punishments, String mirror) {

    public static Tables withPrefix(String prefix) {
        return new Tables(prefix,
                prefix + "meta",
                prefix + "identities",
                prefix + "player_names",
                prefix + "known_ips",
                prefix + "punishments",
                prefix + "ban_mirror");
    }
}
