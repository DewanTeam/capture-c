# ANOBank PaperProxy Manager

This is a **separate standalone executable JAR**. HidenCloud runs this process as the server's main process from `/home/container`. It is not loaded as a Paper or Velocity plugin.

Build output: `manager/build/libs/ANOBank-PaperProxy-Manager.jar`.

## Runtime topology

- Public listener: HidenCloud allocated port, normally `24633`.
- PaperProxy: `127.0.0.1:25565`, working directory `/home/container/paperproxy`.
- Limbo: `127.0.0.1:30000`, working directory `/home/container/limbo`.
- PaperProxy JAR: `/home/container/paperproxy.jar`.
- Limbo JAR: `/home/container/limbo.jar`.
- PaperProxy plugins: `/home/container/paperproxy/plugins/`.

The gateway prefers PaperProxy when its route is enabled and the local port accepts a connection. When PaperProxy is stopped, starting, or unreachable, the gateway falls back to Limbo. The gateway consumes incoming PROXY v2 metadata before forwarding to Limbo, so Limbo receives a normal Minecraft byte stream.

## HidenCloud main process

Run the standalone manager from `/home/container`:

```sh
java -jar ANOBank-PaperProxy-Manager.jar
```

HidenCloud should assign the public port as `SERVER_PORT`. The manager also accepts `anobank.manager.gateway.port` from `server.properties` or a JVM `-D` argument. JVM system properties take precedence over the read-only `server.properties` file, followed by environment values and built-in defaults.

The manager never creates, edits, deletes, or rewrites `server.properties`.

## Configuration in server.properties

Add any settings you need to the existing `/home/container/server.properties`. Do not delete the file; HidenCloud may regenerate it.

```properties
# Public gateway. If omitted, SERVER_PORT is used.
anobank.manager.gateway.bind=0.0.0.0
anobank.manager.gateway.port=24633

# Internal services.
anobank.manager.gateway.paperproxy-host=127.0.0.1
anobank.manager.gateway.paperproxy-port=25565
anobank.manager.gateway.limbo-host=127.0.0.1
anobank.manager.gateway.limbo-port=30000

# Start both services automatically.
anobank.manager.paperproxy.auto-start=true
anobank.manager.limbo.auto-start=true
anobank.manager.paperproxy.auto-restart=true
anobank.manager.limbo.auto-restart=true

# Heap defaults; PaperProxy's custom paperproxy/start.sh remains authoritative.
anobank.manager.paperproxy.xms=1G
anobank.manager.paperproxy.xmx=1G
anobank.manager.limbo.xms=128M
anobank.manager.limbo.xmx=512M

# Use the branch to install from during development. Change to main after PR #1 is merged.
anobank.manager.companion.branch=feature/superpower-plugin

# IMPORTANT: paste the current official TCPShield IPv4/IPv6 CIDR ranges here.
# Do not guess, use example addresses, or leave stale CIDRs in production.
# An empty value rejects all incoming PROXY protocol headers but permits direct clients.
anobank.manager.gateway.tcpshield-cidrs=
```

Equivalent settings can be provided as JVM arguments, for example:

```sh
java -Danobank.manager.gateway.port=24633 -Danobank.manager.companion.branch=feature/superpower-plugin -jar ANOBank-PaperProxy-Manager.jar
```

The manager reads official TCPShield CIDRs from the setting above. Obtain the current list from the TCPShield panel/documentation and update it when their ranges change. TCPShield's Proxy Protocol v2 option must be enabled for the backend set that forwards to this gateway. If an incoming peer address matches a configured TCPShield CIDR, it must send a valid PROXY v2 TCP header; missing or malformed headers from those peers are rejected. An untrusted source attempting to send a PROXY header is also rejected. Ordinary direct clients are accepted without a header.

## PaperProxy configuration

The manager converts both verified TCPShield PROXY v2 and ordinary direct connections into PROXY v1 before sending them to PaperProxy. PaperProxy must accept PROXY protocol at its listener. In `velocity.toml`, configure the equivalent top-level setting for your PaperProxy build:

```toml
haproxy-protocol = true
player-info-forwarding-mode = "none"
```

Keep the listener bound to `127.0.0.1:25565` so clients connect through the public multiplexer rather than bypassing it. Confirm the exact key name is supported by the PaperProxy build you run.

The gateway's verified TCPShield mode is compatible with the hybrid design: TCPShield domains arrive with PROXY v2 metadata; direct-to-HidenCloud domains arrive as normal TCP connections. No TCPShield plugin is required in this PROXY-protocol setup. TCPShield documentation states its native PROXY v2 mode is incompatible with its older TCPShield plugin, and that the proxy-protocol toggle and a suitable firewall should be used instead.

## Single-file command journal

The one user-editable command/output file is:

`/home/container/manager-console.txt`

Append commands at the end of the file, each on its own line and beginning with `> `:

```text
> help
> status
> status paperproxy
> status limbo
> start paperproxy
> stop limbo
> restart paperproxy 10
> restart limbo 5
> gateway status
> gateway limbo
> gateway paperproxy
> proxy version
> limbo version
> update companion
```

The manager appends results to that same file as lines beginning with `| [timestamp]`. Refresh the file in the HidenCloud file manager to read the latest output.

A small hidden `.manager-console.cursor` file stores the read offset and prefix hash so restarting the manager does not replay previous commands. It is internal bookkeeping only; do not edit or delete it manually. If the main journal is replaced or truncated, the manager treats existing content as history and does not replay it. Use only the documented allow-listed commands; arbitrary shell execution is not available through the journal.

- `> restart paperproxy [seconds]` sends PaperProxy's `pp restart <seconds>` command when it is already running; default delay is 10 seconds.
- `> stop limbo` sends Limbo's built-in `stop` command and waits for its graceful exit before using process termination as a fallback.
- `> restart limbo [seconds]` sends `stop`, waits for Limbo to exit, then starts it after the requested delay (default 10 seconds).
- `> proxy <command>` and `> limbo <command>` send console commands to the selected process and append any promptly emitted output to the journal.
- `> update companion` explicitly clones the configured GitHub branch, runs `./gradlew clean build`, verifies the universal plugin JAR contains both Paper and Velocity descriptors, backs up the current plugin, and replaces it. It then restarts PaperProxy; if the operation fails after replacement, the manager attempts to restore the backup and restart the previous plugin.

The manager does not automatically pull or build updates at startup. The update command temporarily drains PaperProxy and new connections fall back to Limbo while the build runs.

## Limbo stop command

The manager uses Limbo's built-in `stop` console command. The official LOOHP Limbo source implements it through `Limbo.getInstance().stopServer()`. The manager waits for the process to exit and only terminates it if the graceful stop does not finish.

## TCPShield notes

TCPShield officially supports Proxy Protocol v2 to forward the original player address. Enable it on the TCPShield backend set and provide the current official proxy CIDR list to the manager. The manager authenticates header origins by source CIDR; the PROXY protocol header itself is not a cryptographic authentication mechanism.

Because your topology also allows direct domains, do not apply a blanket firewall rule that blocks all non-TCPShield clients at this shared gateway. The application accepts ordinary direct connections without trusting any client-supplied PROXY metadata.

## Discord control

JDA slash commands are not included in this build. The file journal is the current supported remote control surface. Its command dispatcher is isolated so a future Discord adapter can call the same allow-listed operations instead of introducing another process-control implementation.

## Limits

- This manager is intended to be the HidenCloud main process and should run only once for this container.
- It expects PaperProxy's service jar and Limbo jar at the paths listed above.
- The manager has no web panel or arbitrary shell executor.
- The manager/PROXY gateway has been compiled and reviewed, but must be tested on the actual HidenCloud port assignment and PaperProxy version before production use.
