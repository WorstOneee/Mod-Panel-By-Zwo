# ModPanel

Painel de moderação para servidores Paper, feito por **Zwo**.

Bans por perfil (UUID) e por IP, bans temporários, mutes de voz e chat, histórico disciplinar completo e
um painel em inventário para a staff. Os dados ficam em **SQLite** (padrão) ou **MySQL/MariaDB**, e
vários servidores podem compartilhar o mesmo banco.

- Paper **1.21.8 – 1.21.11**
- Java **21**

## Instalação

1. Coloque `ModPanel-<versão>.jar` na pasta `plugins/`.
2. Inicie o servidor. Na primeira inicialização o Paper baixa as bibliotecas de banco (HikariCP, driver
   MySQL e SQLite), então o servidor precisa de acesso à internet nessa hora.
3. Ajuste `plugins/ModPanel/config.yml` e `messages.yml` e use `/mp reload`.

### Vindo do SerpensModPanel

Não é preciso fazer nada manualmente. Se a pasta `plugins/SerpensModPanel` existir:

- o `config.yml` antigo é copiado (comandos, permissões e GUI continuam iguais);
- o histórico do `modpanel.db` antigo é importado para o banco configurado, mantendo os IDs;
- se existir apenas o `data.yml` da primeira versão, ele é migrado.

A importação acontece uma única vez e só em banco vazio. Depois de conferir que está tudo certo, o jar
antigo e a pasta antiga podem ser removidos.

## Armazenamento

```yaml
storage:
  type: mysql          # ou sqlite
  table-prefix: 'modpanel_'
  mysql:
    host: 'localhost'
    port: 3306
    database: 'modpanel'
    username: 'modpanel'
    password: 'senha'
```

Trocar de tipo exige reiniciar o servidor. Para levar os dados de um SQLite já em uso para o MySQL,
configure o MySQL, reinicie e rode `/mp importar sqlite confirm` (o banco MySQL precisa estar vazio).

Todo acesso ao banco roda fora da thread principal, então uma consulta lenta não trava o servidor.

### Vários servidores no mesmo banco

```yaml
network:
  enabled: true
  server-id: 'lobby'   # diferente em cada servidor
```

Com a rede ativa:

- um ban aplicado em um servidor é aplicado na banlist dos outros na próxima sincronização
  (`ban-sync.interval-seconds`) e o jogador é desconectado se estiver online;
- desbans e expirações também se propagam;
- o login é conferido direto no banco (`login-check`), então o jogador já é barrado mesmo antes da
  sincronização;
- mutes de chat são atualizados no mesmo intervalo.

Cada servidor registra quais punições já aplicou na própria banlist. Assim um `/pardon` feito no console
de um servidor é entendido como remoção externa, enquanto um ban vindo de outro servidor é aplicado.

## Comandos

| Comando | Descrição |
| --- | --- |
| `/modpanel` (`/mpanel`) | Painel principal |
| `/banpanel`, `/tempbanpanel`, `/ipbanpanel`, `/voicepanel` | Listas de punições ativas |
| `/mpperfil [jogador] [página]` | Perfil e histórico |
| `/mpban <jogador> <motivo>` | Ban permanente (perfil e, se for seguro, o último IP) |
| `/mpbanperfil <jogador\|uuid> <motivo>` (`/mpbanprofile`) | Ban permanente **só do perfil/UUID**: nunca bane IP nem derruba outras contas. Indicado para automações como a ZwoVulcanBridge |
| `/mptempban <jogador> <tempo> <motivo>` | Ban temporário (`30m`, `1h30m`, `7d`, `1mo`...) |
| `/mpipban <jogador\|ip> <motivo>` | Ban de IP |
| `/mpunban <jogador>` | Remove o ban do jogador e o IP vinculado ao mesmo caso |
| `/mpunbanip <ip>` | Remove o ban de IP e os perfis do mesmo caso |
| `/mpunbanall confirm` | Remove todos os bans gerenciados |
| `/mpmute <jogador> <minutos\|tempo> [motivo]` | Mute de voz e chat |
| `/mpunmute <jogador>` | Remove o mute |
| `/mpunmuteall confirm` | Remove todos os mutes |
| `/mpmuteradius <raio> <minutos\|tempo> [motivo]` | Muta quem está por perto |
| `/mpsyncbans` | Sincroniza banco e banlist do Paper na hora |
| `/mp status` · `/mp reload` · `/mp importar sqlite confirm` | Administração |

No painel, clique esquerdo em uma punição abre a confirmação de remoção e clique direito abre o perfil
do jogador.

## Permissões

| Permissão | Uso |
| --- | --- |
| `modpanel.admin` | Tudo abaixo (exceto bypass/protected) |
| `modpanel.main` | Acesso básico ao painel |
| `modpanel.view` | Ver painéis, perfis e status |
| `modpanel.ban` / `modpanel.ipban` | Banir perfil / IP |
| `modpanel.unban` / `modpanel.unban-ip` | Desbanir perfil / IP |
| `modpanel.mute` / `modpanel.unmute` | Mutar / desmutar |
| `modpanel.mute-radius` | Mute em área |
| `modpanel.bulk` | Desbanir/desmutar todos |
| `modpanel.sync` | `/mpsyncbans` |
| `modpanel.reload` | `/mp reload` e importação |
| `modpanel.bypass` | Ignorado pelo mute em área |
| `modpanel.protected` | Não pode ser banido ou mutado pelo painel |

Os nomes podem ser trocados na seção `permissions` do `config.yml`. Quem veio do SerpensModPanel
continua com os nós `serpens.modpanel.*`, porque o config antigo é reaproveitado.

## Mutes

- **Voz**: executa os comandos de `commands.voice-mute-command` / `voice-unmute-command` (por padrão o
  LuckPerms tirando `voicechat.speak` temporariamente).
- **Chat**: o próprio ModPanel bloqueia o chat e os comandos de `chat-mute.blocked-commands`. Se você usa
  outro plugin de chat, dá para configurar `chat-mute-command` também.

## Compilando

```bash
mvn package
```

O jar fica em `target/ModPanel-<versão>.jar`. Os testes de banco rodam com SQLite sempre e com MySQL
quando a variável `MODPANEL_MYSQL_HOST` estiver definida.
