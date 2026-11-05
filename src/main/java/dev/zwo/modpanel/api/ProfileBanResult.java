package dev.zwo.modpanel.api;

import java.util.UUID;

/**
 * Resultado verificável de uma operação da {@link ProfileBanApi}.
 *
 * @param status       o que aconteceu
 * @param uuid         perfil consultado
 * @param punishmentId id do registro no histórico do painel ({@code -1} se não houver)
 * @param caseId       caso do registro ({@code null} se não houver)
 * @param expiresAt    fim do ban em epoch millis ({@code null} = permanente ou sem registro)
 * @param verified     {@code true} quando o painel conferiu, depois de gravar, que o registro está ativo
 *                     no banco <b>e</b> que o perfil está na banlist do Paper
 * @param ipBanned     sempre {@code false} nesta API: nenhum IP é banido
 * @param message      detalhe legível (PT-BR) para log e revisão
 */
public record ProfileBanResult(Status status, UUID uuid, long punishmentId, String caseId, Long expiresAt,
                               boolean verified, boolean ipBanned, String message) {

    public enum Status {
        /** Registro criado agora. Confira {@link #verified()}. */
        APPLIED,
        /** Já existia um ban de perfil ativo; nada foi criado. */
        ALREADY_BANNED,
        /** Nenhum ban de perfil ativo (só em {@link ProfileBanApi#activeProfileBan}). */
        NOT_BANNED,
        /** Pedido recusado (dados inválidos, alvo protegido, duração acima do limite). Nada foi gravado. */
        REJECTED,
        /** Falha de banco ou da banlist. O ban nativo é revertido quando o registro não pôde ser salvo. */
        FAILED
    }

    public boolean success() {
        return (status == Status.APPLIED || status == Status.ALREADY_BANNED) && verified;
    }

    public static ProfileBanResult of(Status status, UUID uuid, String message) {
        return new ProfileBanResult(status, uuid, -1L, null, null, false, false, message);
    }
}
