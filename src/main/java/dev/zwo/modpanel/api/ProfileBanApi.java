package dev.zwo.modpanel.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * API do ModPanel para automações (ex.: ZwoVulcanBridge), registrada no ServicesManager do Bukkit.
 *
 * <p>Só bane o <b>perfil (UUID)</b>. Nunca grava, bane ou consulta IP, não derruba outras contas e
 * não executa comandos ({@code /mpban}, {@code /mptempban}, {@code /ban-ip}). A punição entra no
 * histórico normal do painel e pode ser removida com {@code /mpunban}.</p>
 *
 * <p>Os métodos podem ser chamados de qualquer thread. Os futures completam fora da thread principal
 * e nunca completam com exceção: qualquer erro vira {@link ProfileBanResult.Status#FAILED}.</p>
 */
public interface ProfileBanApi {

    /** Versão do contrato desta interface. Muda só em alterações incompatíveis. */
    int API_VERSION = 1;

    /** Versão do contrato implementada pelo ModPanel carregado (compare com a que você espera). */
    int apiVersion();

    /**
     * Ban temporário só do perfil. Se o perfil já tiver um ban de perfil ativo, nada é criado e o
     * resultado é {@link ProfileBanResult.Status#ALREADY_BANNED}. O mesmo {@code requestId} nunca
     * gera dois registros.
     */
    CompletableFuture<ProfileBanResult> tempBanProfile(ProfileBanRequest request);

    /** Ban de perfil ativo do UUID no banco do painel, para conferência. */
    CompletableFuture<ProfileBanResult> activeProfileBan(UUID uuid);
}
