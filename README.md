# SuperpowerPlugin

Paper/Purpur plugin for Java 25, targeting Purpur/Paper 1.21.5+ and the supplied Purpur 26.1.2 test server.

## Implemented systems

- Vault economy purchases for the configured six-power catalogue.
- A PDC-marked Superpower Activator item, so an ordinary renamed diamond cannot spoof it.
- LuckPerms-backed rank purchases and gifts, including configured rank upgrades. The default LuckPerms group is not treated as a paid rank; paid rank ownership is determined from configured rank groups.
- Rank assignment is attempted and saved before Vault withdraws money. If payment fails, the LuckPerms change is rolled back. Transactions are serialized per payer/recipient and recorded in `transactions.yml`.
- UUID-based player power, rank and cooldown persistence in `players.yml`.
- Configurable timed power effects and the confirmed rank duration progression: base 30 seconds, then +2/+4/+6 seconds for the three sample rank tiers.
- Firey is implemented as a particle-only fireball visual with a five-second shot interval; it does not create damaging projectiles or alter blocks.
- DialogEngine menus shipped under `src/main/resources/dialogs/shop.yml`, registered dynamically through the DialogEngine 0.8.x API. The G launcher opens a rank shop and superpower shop on supported clients.

## Required server plugins

- Vault and a Vault economy provider (the supplied server log showed EssentialsX Economy hooked through Vault).
- LuckPerms 5.5 or compatible.
- CommandAPI 12.x. CommandAPI was not present in the supplied server startup log, so install it before this plugin.
- DialogEngine 0.8.x is optional for command functionality but required for the native dialogs and G Quick Actions.
- PlaceholderAPI is not required by this implementation; DialogEngine placeholders are registered by this plugin.

Native DialogEngine dialogs and Quick Actions require a supported Minecraft client (1.21.6+ per DialogEngine documentation). The server needs DialogEngine 1.21.7+; Purpur 26.1.2 meets that server minimum.

## Commands

- `/rankshop` — open the rank shop (or show command fallback when DialogEngine is absent).
- `/rankshop buy <rank_id>` — purchase an explicitly selected rank.
- `/rankshop gift <online_player> <rank_id>` — gift a rank to an online player.
- `/rankshop benefits` — show configured benefits.
- `/superpower` — open the G-launcher menu or show command help.
- `/superpower shop` — open/list the power shop.
- `/superpower buy <power_id>` — buy a configured power.
- `/superpower item` — give yourself the PDC-marked activator.
- `/superpower dismiss` — remove your current power.
- `/superpower giveitem <online_player>` — operator/admin utility to give an activator.

## Configure before a real purchase test

`config.yml` deliberately marks the current sample ranks (`veteran`, `elite`, `legend`) and sample prices as provisional. The WhatsApp text did not confirm the final names, prices, perks or LuckPerms group IDs. The three sample `group` values currently match their sample IDs; make sure those groups exist in LuckPerms or edit the values before enabling purchases. The plugin checks the target group exists before assigning it or taking money.

Only the duration bonus is confirmed by Dead Bush's 20 November instruction: default timed power duration 30 seconds, +2 seconds for the first rank, +4 for the second, +6 for the top sample rank. Additional rank perks should be added only after Dead Bush supplies their actual list.

The gift form currently requires the recipient to be online. Offline gifting is not enabled in this first implementation because the command and confirmation flow resolve the recipient through Bukkit's online-player cache.

## Build

Use JDK 25 and Gradle 9.9.2:

```bash
gradle --no-daemon clean build
```

The JAR is emitted as `build/libs/SuperpowerPlugin.jar`.