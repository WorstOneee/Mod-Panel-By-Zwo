package dev.zwo.modpanel.api;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Pedido de ban temporário só do perfil.
 *
 * @param uuid      perfil a banir (obrigatório)
 * @param name      nome atual, usado no histórico e na banlist (opcional)
 * @param duration  duração (positiva e dentro do limite {@code limits.max-tempban-days} do painel)
 * @param reason    motivo exibido ao jogador e no histórico
 * @param actor     quem aplicou, como aparece no histórico (ex.: "ZwoVulcanBridge")
 * @param requestId identificador único do pedido; repetir o mesmo id nunca duplica o registro
 */
public record ProfileBanRequest(UUID uuid, String name, Duration duration, String reason, String actor,
                                String requestId) {

    public ProfileBanRequest {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(requestId, "requestId");
    }
}
