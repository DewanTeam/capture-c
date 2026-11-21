# SuperpowerPlugin — Universal Paper/Purpur + PaperProxy JAR

One Java 25 JAR contains both platform entrypoints:

- Paper/Purpur backend: optional family auto-transfer, optional NeoCityFilter chat replacement, and opt-in Superpower/RankShop.
- Velocity/PaperProxy companion: requests the native transfer cookie and routes the player to an approved origin backend.
- The HidenCloud process manager/multiplexer remains a separate standalone JAR.

## Compatibility

- Java 25.
- Paper/Purpur API baseline 26.1.2+; built against Paper API 26.1.2.
- Velocity/PaperProxy API baseline 3.4.0.
- ViaVersion and ViaBackwards remain proxy-side dependencies.
- The setup is designed to keep player-info-forwarding-mode set to none.

Both platform descriptors are packaged in the same JAR: paper-plugin.yml and the generated velocity-plugin.json. This build has passed GitHub Actions compilation and archive inspection, but still needs live testing on your exact PaperProxy and Purpur servers.

## Backend setup

1. Put SuperpowerPlugin.jar in the backend plugins folder.
2. Start once to generate plugins/SuperpowerPlugin/config.yml.
3. All optional modules default to disabled.
4. To enable family routing, set modules.family-routing.enabled to true and fill in family-routing.server-id, family-routing.transfer-target, trusted-proxy-peers, and direct-bypass-hosts.

Example UCAHub settings (replace the peer placeholder with the actual raw TCP peer address observed by UCAHub):

    modules:
      family-routing:
        enabled: true
    family-routing:
      server-id: ucahub
      transfer-target:
        host: uca.pwna.cc.cd
        port: 25565
      trusted-proxy-peers:
        - REPLACE_WITH_BACKEND_OBSERVED_PROXY_IP
      direct-bypass-hosts:
        - direct.ucahub.mysrv.us

Accepted trusted-proxy-peers values are exact IPs, hostnames, and IPv4/IPv6 CIDR ranges. If the list is empty, automatic transfer fails closed to prevent loops. Temporarily enable family-routing.debug.log-peer-addresses to see the peer address your backend actually receives.

Family routing runs at Paper's initial configuration connection event, before the ordinary join/login flow. Floodgate players are ignored. The backend stores its server ID in a transfer cookie; the cookie is only a routing hint and is not authentication.

The direct-bypass-hosts setting is useful for a direct hostname that should never auto-transfer, such as a staff/admin endpoint.

## PaperProxy setup

Put the same SuperpowerPlugin.jar in PaperProxy's plugins folder. Start once to generate plugins/SuperpowerPlugin/config.properties, then set origin-router.enabled=true. Its cookie namespace/key must match the backend settings. allowed-servers must contain exact backend names from velocity.toml.

Example proxy config:

    origin-router.enabled=true
    cookie.namespace=network
    cookie.key=origin_server
    cookie.request-timeout-ms=1500
    fallback-server=bushsmp
    allowed-servers=bushsmp,ucahub,daybreak,riely
    log.cookie-decisions=true

If no valid origin cookie arrives, the proxy preserves its normal initial server/fallback. Cookie values are matched only against the explicit allowed-servers list. No Redis service or shared Redis secret is required.

## Optional NeoCityFilter

The module and filter are disabled by default. Enable modules.neocity-filter.enabled and chat-filter.enabled, then set chat-filter.target and chat-filter.replacement. This is the lightweight chat text replacement only; the previous Redis ticket handoff and Bedrock auto-login feature are not automatically enabled.

## Optional Superpowers and RankShop

The generated default config intentionally contains no power catalogue or rank catalogue. This prevents installing the universal plugin on partner backends from activating Superpowers accidentally.

On BushSMP only, set modules.superpowers.enabled to true and append the required superpowers, cooldowns, economy, and/or ranks configuration sections. Vault plus an economy provider is required for purchases; LuckPerms is required when ranks are configured; CommandAPI 12.x is required for these command modules. DialogEngine 0.8.x is optional and provides native dialogs and the G Quick Action launcher.

The ᴛᴋ currency symbol is configured with economy.currency-symbol in the BushSMP-only configuration. The sample rank names, prices, group IDs, and additional perks are provisional until Dead Bush confirms the final catalogue. The confirmed duration progression is 30 seconds base, then +2/+4/+6 seconds for the three sample ranks.

Available commands appear only when their corresponding module/configuration is active:

- /autotransfer, /autotransfer on, /autotransfer off, /autotransfer toggle, /autotransfer status.
- /superpower, /superpower shop, /superpower buy <power_id>, /superpower dismiss, /superpower item, /superpower reload.
- /rankshop, /rankshop buy <rank_id>, /rankshop gift <online_player> <rank_id>, /rankshop benefits.
- /superpower gift <online_player> <rank_id> is registered only when both the Superpower and RankShop modules are configured.

DialogEngine menus use [back] for navigation and Close for exit. If DialogEngine is absent, command-based shop fallbacks remain available.

## AnimatedScoreboard to TAB

The old `archive.zip` contained AnimatedScoreboard's active `defaultscoreboard`: BushSMP title, Tokens balance, Jobs, and online count. A TAB header/footer migration template is included at `docs/TAB-header-footer.yml`.

Merge its `header-footer` and `scoreboard.enabled: false` sections into the existing `plugins/TAB/config.yml`; do not replace the whole TAB config. Header/footer is displayed as part of the vanilla player list while the player holds Tab. The old AnimatedScoreboard sidebar is not recreated.

The migrated balance line appends `ᴛᴋ` to `%vault_eco_balance_commas%`. This only changes the TAB display. Spawn Top Money holograms are managed by a separate hologram/leaderboard plugin; that configuration was not present in `archive.zip`, so its dollar-sign formatting has not been changed yet.

## Build

Use JDK 25 and the checked-in Gradle Wrapper:

    ./gradlew --no-daemon clean build

The universal plugin output is build/libs/SuperpowerPlugin.jar. The single repository workflow builds with the wrapper and only publishes/deploys from main; feature and pull-request builds are build-only.

## Live-test checklist

1. Start PaperProxy with the JAR while origin routing is disabled.
2. Start one test backend with family routing enabled and a verified trusted-proxy-peers entry.
3. Verify direct-bypass host and Floodgate bypass.
4. Verify cookie routing for every forced-host destination and fallback routing when no cookie exists.
5. Enable Superpowers on BushSMP only, after confirming the economy provider and LuckPerms group catalogue.

The separate HidenCloud manager/multiplexer is not part of this plugin JAR. Its PROXY-v2/CIDR normalisation, single-file command journal, and companion-plugin updater are separate manager tasks.