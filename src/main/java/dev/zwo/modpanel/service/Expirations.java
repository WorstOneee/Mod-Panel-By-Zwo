package dev.zwo.modpanel.service;

import dev.zwo.modpanel.model.PunishmentRecord;

import java.util.Collection;

/** Regras de prazo quando há mais de uma punição ativa para o mesmo alvo. */
final class Expirations {

    private Expirations() {
    }

    /** Prazo que vale para o conjunto: null se alguma for permanente, senão o maior. */
    static Long effective(Collection<PunishmentRecord> active) {
        Long longest = null;
        for (PunishmentRecord record : active) {
            if (record.expiresAt() == null) {
                return null;
            }
            longest = longest == null ? record.expiresAt() : Math.max(longest, record.expiresAt());
        }
        return longest;
    }

    /** Prazo a aplicar quando uma nova punição chega por cima das existentes. */
    static Long merge(Collection<PunishmentRecord> existing, Long requested) {
        if (requested == null) {
            return null;
        }
        if (existing.isEmpty()) {
            return requested;
        }
        Long current = effective(existing);
        return current == null ? null : Math.max(current, requested);
    }
}
