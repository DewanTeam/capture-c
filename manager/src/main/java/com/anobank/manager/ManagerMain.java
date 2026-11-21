package com.anobank.manager;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Local-only allow-listed PaperProxy/Limbo process manager with a single-file command journal. */
public final class ManagerMain {
  private static final int MAX_ARGS = 32;
  private static final int MAX_ARG_LENGTH = 512;
  private final Path launchDir = Path.of("").toAbsolutePath().normalize();
  private final Path root;
  private final Path consoleJournal;
  private final Path consoleCursor;
  private final Properties serverProperties = new Properties();
  private volatile long consoleOffset;
  private volatile String consolePrefixHash = "";
  private final int logRetentionHours;
  private final TcpFailoverGateway gateway;
  private final Map<String, ManagedService> services = new LinkedHashMap<>();
  private final ScheduledExecutorService managerWorker = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "manager-background-worker"); t.setDaemon(true); return t;
  });
  private final ScheduledExecutorService maintenanceWorker = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "manager-log-retention"); t.setDaemon(true); return t;
  });
  private final java.util.concurrent.atomic.AtomicBoolean shutdownStarted = new java.util.concurrent.atomic.AtomicBoolean(false);

  private ManagerMain() throws Exception {
    // FreePanel starts this process from /home/container. This directory is the manager root.
    // server.properties is read-only. JVM -D settings take precedence over anobank.manager.* keys in it.
    root = launchDir;
    Files.createDirectories(root);
    loadServerPropertiesReadOnly();
    consoleJournal = root.resolve("manager-console.txt").normalize();
    consoleCursor = root.resolve(".manager-console.cursor").normalize();
    if (Files.isSymbolicLink(consoleJournal) || Files.isSymbolicLink(consoleCursor))
      throw new IOException("manager-console.txt and its cursor must not be symlinks.");
    if (Files.notExists(consoleJournal)) Files.createFile(consoleJournal);
    initializeConsoleCursor();
    // Optional settings: JVM -D overrides read-only server.properties, then defaults.
    logRetentionHours = intSetting("anobank.manager.log-retention-hours", 7, 1, 720);
    int gatewayPort = gatewayPort();
    List<String> trustedTcpShieldCidrs = csvSetting("anobank.manager.gateway.tcpshield-cidrs", "");
    gateway = gatewayPort == 0 ? null : new TcpFailoverGateway(
        setting("anobank.manager.gateway.bind", "0.0.0.0"), gatewayPort,
        setting("anobank.manager.gateway.paperproxy-host", "127.0.0.1"),
        intSetting("anobank.manager.gateway.paperproxy-port", 25565, 1, 65535),
        setting("anobank.manager.gateway.limbo-host", "127.0.0.1"),
        intSetting("anobank.manager.gateway.limbo-port", 30000, 1, 65535),
        trustedTcpShieldCidrs);
    addService("paperproxy");
    addService("limbo");

    // Remote management uses the single append-only manager-console.txt journal.

    cleanupOldLogsSafely();
  }

  private void addService(String id) throws IOException {
    if (!Set.of("paperproxy", "limbo").contains(id)) throw new IllegalArgumentException("Unknown service: " + id);

    String jarName = id.equals("paperproxy") ? "paperproxy.jar" : "limbo.jar";
    Path jar = root.resolve(jarName).normalize();
    String folderName = jar.getFileName().toString().replaceFirst("(?i)\\.jar$", "");
    Path cwd = root.resolve(folderName).normalize();
    if (!cwd.startsWith(root)) throw new IOException("Service directory must stay inside the manager root.");
    Files.createDirectories(cwd);
    if (Files.isSymbolicLink(cwd) || !Files.isDirectory(cwd, NOFOLLOW_LINKS))
      throw new IOException(id + " working directory must be a real directory, not a symlink.");
    Files.createDirectories(cwd.resolve("logs"));
    if (id.equals("paperproxy")) Files.createDirectories(cwd.resolve("plugins"));

    String xms = id.equals("paperproxy") ? heapSetting("anobank.manager.paperproxy.xms", "1G") : heapSetting("anobank.manager.limbo.xms", "128M");
    String xmx = id.equals("paperproxy") ? heapSetting("anobank.manager.paperproxy.xmx", "1G") : heapSetting("anobank.manager.limbo.xmx", "512M");
    List<String> jvmArgs = List.of();
    List<String> appArgs = id.equals("limbo") ? List.of("--nogui") : List.of();
    boolean autoRestart = booleanSetting("anobank.manager." + id + ".auto-restart", true);
    services.put(id, new ManagedService(id, jar, cwd, xms, xmx, jvmArgs, appArgs, autoRestart));
  }

  public static void main(String[] args) throws Exception {
    ManagerMain app = new ManagerMain();
    Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown, "manager-shutdown"));
    System.out.println("Root: " + app.root);
    System.out.println("HidenCloud server.properties: read-only; only anobank.manager.* settings are read. JVM -D overrides file values.");
    System.out.println("PaperProxy JVM flags remain in paperproxy/start.sh; server.properties is never modified.");
    if (app.gateway == null) System.out.println("[gateway] disabled: set SERVER_PORT or -Danobank.manager.gateway.port=<allocated port> to enable the public PaperProxy/Limbo gateway.");
    else {
      try { app.gateway.start(); }
      catch (Exception e) { throw new IllegalStateException("Could not start the public Minecraft gateway: " + safeError(e), e); }
    }
    System.out.println("PaperProxy: executes " + app.root.resolve("paperproxy").resolve("start.sh") + " with working directory " + app.root.resolve("paperproxy") + "; start.sh controls the JAR path and JVM flags.");
    System.out.println("Limbo JAR: " + app.root.resolve("limbo.jar") + " | working directory: " + app.root.resolve("limbo"));
    System.out.println("Manager command journal: " + app.consoleJournal + " (append commands as '> command'; results are appended as '| ...').");
    System.out.println("Log retention: delete stale files older than " + app.logRetentionHours + " hours under paperproxy/logs and limbo/logs; keep latest.log and active directories.");
    System.out.println("Available services: " + String.join(", ", app.services.keySet()));
    System.out.println("Console: PaperProxy output is passed through raw (ANSI preserved); Limbo output is line-prefixed. File command results are appended to manager-console.txt.");
    System.out.println("Console input: normal lines -> PaperProxy; !limbo <command> -> Limbo; !manager help -> manager controls; bare 'stop' is reserved for FreePanel shutdown.");
    System.out.println("File journal: append '> status', '> start paperproxy', '> stop limbo', '> restart paperproxy 10', '> gateway status', '> proxy <command>', '> limbo <command>', or '> update companion'. Output is appended as '| ...'.");
    System.out.println("Scheduled restarts are configured in paperproxy/paperproxy.toml under [restart].times; the manager does not add a second restart schedule.");
    app.managerWorker.scheduleWithFixedDelay(app::pollConsoleJournalSafely, 1, 1, TimeUnit.SECONDS);
    app.maintenanceWorker.scheduleWithFixedDelay(app::cleanupOldLogsSafely, 1, 1, TimeUnit.HOURS);
    app.startConfiguredServices();
    app.startConsoleInput();
  }

  private void startConfiguredServices() {
    for (String id : List.of("paperproxy", "limbo")) {
      ManagedService s = services.get(id);
      if (s == null || !booleanSetting("anobank.manager." + id + ".auto-start", true)) continue;
      try { s.start(null, null); System.out.println("[manager] started " + id + " (auto-start)"); }
      catch (Exception e) { System.err.println("[manager] could not auto-start " + id + ": " + safeError(e)); }
    }
  }

  private void startConsoleInput() {
    Thread t = new Thread(() -> {
      try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = input.readLine()) != null) {
          try { dispatchConsoleLine(line); }
          catch (Exception e) { System.err.println("[console] " + safeError(e)); }
        }
      } catch (IOException e) { System.err.println("[console] input closed: " + safeError(e)); }
    }, "manager-console-input");
    // Keep this thread non-daemon: shutdown() stops the queue worker before child cleanup,
    // and the console thread must keep the JVM alive until coordinated shutdown completes.
    t.setDaemon(false);
    t.start();
  }

  private void dispatchConsoleLine(String line) throws Exception {
    if (line == null || line.length() > MAX_ARG_LENGTH || line.indexOf('\0') >= 0 || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
      System.err.println("[console] rejected invalid/overlong input"); return;
    }
    // HidenCloud/FreePanel sends the literal `stop` through stdin on panel stop/restart.
    // Reserve that exact input for coordinated shutdown rather than forwarding it to the proxy.
    if (line.trim().equalsIgnoreCase("stop")) {
      System.out.println("[manager] FreePanel stop signal received; gracefully stopping PaperProxy and stopping Limbo...");
      shutdown();
      System.exit(0);
      return;
    }
    if (line.startsWith("!manager") && (line.length() == 8 || Character.isWhitespace(line.charAt(8)))) {
      String[] p = line.trim().split("\\s+", 3);
      String action = p.length > 1 ? p[1].toLowerCase(Locale.ROOT) : "help";
      String target = p.length > 2 ? p[2].trim().toLowerCase(Locale.ROOT) : "";
      switch (action) {
        case "help" -> System.out.println("[manager] !manager help | status | start <service> | stop <service> | restart <service> | gateway [status|paperproxy|limbo] | shutdown; normal lines go to PaperProxy; typing end makes PaperProxy restart after exit; !limbo <command> targets Limbo. A bare stop is reserved for FreePanel shutdown.");
        case "status" -> { for (ManagedService s : services.values()) System.out.println("[manager] " + s.statusJson()); if (gateway != null) System.out.println("[manager] gateway " + gateway.status()); }
        case "gateway" -> {
          if (gateway == null) System.out.println("[gateway] disabled; configure SERVER_PORT or -Danobank.manager.gateway.port.");
          else if (target.equals("status") || target.isEmpty()) System.out.println("[gateway] " + gateway.status());
          else if (target.equals("limbo")) { gateway.paperProxyStopping(); System.out.println("[gateway] new connections routed to Limbo."); }
          else if (target.equals("paperproxy")) { gateway.paperProxyStarting(); System.out.println("[gateway] PaperProxy selected when its local listener is ready."); }
          else System.err.println("[manager] usage: !manager gateway [status|paperproxy|limbo]");
        }
        case "start", "stop", "restart" -> {
          ManagedService s = services.get(target);
          if (s == null) { System.err.println("[manager] unknown or disabled service: " + target); return; }
          if (action.equals("start")) s.start(null, null);
          else if (action.equals("stop")) s.stop();
          else s.restart(null, null);
          System.out.println("[manager] " + action + " requested for " + target + ": " + s.statusJson());
        }
        case "shutdown" -> { System.out.println("[manager] shutting down manager and managed services"); shutdown(); System.exit(0); }
        default -> System.err.println("[manager] unknown action; type !manager help");
      }
      return;
    }
    if (line.startsWith("!limbo ")) { forwardConsole("limbo", line.substring(7)); return; }
    if (line.startsWith("!paperproxy ")) { forwardConsole("paperproxy", line.substring(11)); return; }
    forwardConsole("paperproxy", line);
  }

  private void forwardConsole(String serviceId, String command) throws IOException {
    ManagedService s = services.get(serviceId);
    if (s == null) { System.err.println("[console] service is disabled or not configured: " + serviceId); return; }
    s.sendConsoleLine(command);
  }

  private static String quote(String s) {
    StringBuilder out = new StringBuilder("\"");
    for (int i=0; i<s.length(); i++) {
      char c=s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
        default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
      }
    }
    return out.append('"').toString();
  }
  private void initializeConsoleCursor() throws IOException {
    if (Files.exists(consoleCursor, NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(consoleCursor) || !Files.isRegularFile(consoleCursor, NOFOLLOW_LINKS))
        throw new IOException("Manager console cursor must be a regular file.");
      Properties cursor = new Properties();
      try (Reader reader = Files.newBufferedReader(consoleCursor, StandardCharsets.UTF_8)) { cursor.load(reader); }
      try { consoleOffset = Long.parseLong(cursor.getProperty("offset", "0")); }
      catch (NumberFormatException e) { throw new IOException("Invalid manager console cursor offset.", e); }
      consolePrefixHash = cursor.getProperty("prefix-sha256", "");
      long size = Files.size(consoleJournal);
      if (consoleOffset < 0 || consoleOffset > size || !prefixMatches()) {
        // A panel editor replaced/truncated the file. Do not replay old commands.
        consoleOffset = size;
        consolePrefixHash = sha256Prefix(consoleOffset);
        saveConsoleCursor();
        appendJournalOutput("The command file was rewritten. Existing lines were not replayed; append new '> ' commands at the end.");
      }
    } else {
      // A pre-existing file is treated as history on first install. New commands
      // are appended after this starting offset, so old examples are never replayed.
      consoleOffset = Files.size(consoleJournal);
      consolePrefixHash = sha256Prefix(consoleOffset);
      saveConsoleCursor();
    }
  }

  private void saveConsoleCursor() throws IOException {
    Properties cursor = new Properties();
    cursor.setProperty("offset", Long.toString(consoleOffset));
    cursor.setProperty("prefix-sha256", consolePrefixHash);
    Path temporary = consoleCursor.resolveSibling(consoleCursor.getFileName() + ".tmp");
    try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
      cursor.store(writer, "Managed command journal cursor; do not edit manually.");
    }
    try { Files.move(temporary, consoleCursor, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
    catch (AtomicMoveNotSupportedException e) { Files.move(temporary, consoleCursor, StandardCopyOption.REPLACE_EXISTING); }
  }

  private boolean prefixMatches() throws IOException {
    if (consolePrefixHash == null || consolePrefixHash.isBlank()) return consoleOffset == 0;
    return consolePrefixHash.equals(sha256Prefix(consoleOffset));
  }

  private String sha256Prefix(long length) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(consoleJournal)) {
        byte[] buffer = new byte[8192];
        long remaining = length;
        int read;
        while (remaining > 0 && (read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
          digest.update(buffer, 0, read);
          remaining -= read;
        }
        if (remaining != 0) throw new EOFException("Command journal changed while calculating its cursor.");
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  private void pollConsoleJournalSafely() {
    try { pollConsoleJournal(); }
    catch (Exception e) { System.err.println("[command-file] " + safeError(e)); }
  }

  private synchronized void pollConsoleJournal() throws Exception {
    long size = Files.size(consoleJournal);
    if (size < consoleOffset || !prefixMatches()) {
      consoleOffset = size;
      consolePrefixHash = sha256Prefix(consoleOffset);
      saveConsoleCursor();
      appendJournalOutput("Command file was replaced/truncated; refusing to replay existing commands. Append new lines beginning with '> '.");
      return;
    }
    if (size == consoleOffset) return;

    long pendingBytes = size - consoleOffset;
    if (pendingBytes > 1024 * 1024) {
      System.err.println("[command-file] more than 1 MiB of unprocessed input; archive/reset manager-console.txt before sending more commands.");
      return;
    }
    byte[] bytes = new byte[(int) pendingBytes];
    try (RandomAccessFile file = new RandomAccessFile(consoleJournal.toFile(), "r")) {
      file.seek(consoleOffset);
      file.readFully(bytes);
    }
    // Do not append output while the SFTP editor is still writing a partial final
    // line; that would splice manager output into the user's unfinished command.
    if (bytes.length == 0 || bytes[bytes.length - 1] != '\n') return;

    String block = new String(bytes, StandardCharsets.UTF_8);
    int consumed = bytes.length;
    consoleOffset += consumed;
    consolePrefixHash = sha256Prefix(consoleOffset);
    saveConsoleCursor(); // Persist before executing; a manager restart cannot duplicate commands.

    for (String rawLine : block.split("\n")) {
      String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
      if (line.startsWith("> ")) {
        String command = line.substring(2).trim();
        if (!command.isEmpty()) {
          String output = executeJournalCommand(command);
          appendJournalOutput(output);
        }
      }
    }
  }

  private String executeJournalCommand(String command) {
    if (command.length() > MAX_ARG_LENGTH || command.indexOf('\0') >= 0 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0)
      return "Rejected: command is overlong or contains control characters.";
    String[] parts = command.trim().split("\\s+", 4);
    String action = parts[0].toLowerCase(Locale.ROOT);
    try {
      switch (action) {
        case "help" -> { return "Commands: > status [paperproxy|limbo], > start <service>, > stop <service>, > restart <service> [seconds], > gateway <status|paperproxy|limbo>, > proxy <console command>, > limbo <console command>, > update companion."; }
        case "status" -> {
          if (parts.length == 1) return services.values().stream().map(ManagedService::statusJson).reduce((a,b) -> a + "\n" + b).orElse("No services configured.") + (gateway == null ? "\ngateway disabled" : "\ngateway " + gateway.status());
          ManagedService target = services.get(parts[1].toLowerCase(Locale.ROOT));
          return target == null ? "Unknown service: " + parts[1] : target.statusJson();
        }
        case "start", "stop", "restart" -> {
          if (parts.length < 2) return "Usage: > " + action + " <paperproxy|limbo>";
          String targetId = parts[1].toLowerCase(Locale.ROOT);
          ManagedService target = services.get(targetId);
          if (target == null) return "Unknown service: " + targetId;
          if (action.equals("start")) target.start(null, null);
          else if (action.equals("stop")) target.stop();
          else {
            int seconds = parts.length >= 3 ? Integer.parseInt(parts[2]) : 10;
            if (seconds < 1 || seconds > 86400) return "Restart delay must be between 1 and 86400 seconds.";
            if (targetId.equals("paperproxy") && target.isRunning()) {
              target.sendConsoleLine("pp restart " + seconds);
              return "Sent 'pp restart " + seconds + "' to PaperProxy; the gateway is drained until the process restarts.";
            }
            if (targetId.equals("limbo")) {
              target.stop();
              managerWorker.schedule(() -> {
                try {
                  target.start(null, null);
                  appendJournalOutput("Limbo restarted after the requested " + seconds + " second delay.");
                } catch (Exception restartError) {
                  try { appendJournalOutput("Limbo restart failed: " + safeError(restartError)); }
                  catch (IOException outputError) { System.err.println("[manager] Limbo restart failed: " + safeError(restartError)); }
                }
              }, seconds, TimeUnit.SECONDS);
              return "Limbo received its built-in stop command and exited gracefully; start scheduled in " + seconds + " seconds.";
            }
            target.restart(null, null);
          }
          return action + " completed for " + targetId + ": " + target.statusJson();
        }
        case "gateway" -> {
          if (gateway == null) return "Gateway is disabled; configure SERVER_PORT or anobank.manager.gateway.port.";
          String target = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "status";
          if (target.equals("status")) return gateway.status();
          if (target.equals("limbo")) { gateway.paperProxyStopping(); return "New connections route to Limbo."; }
          if (target.equals("paperproxy")) { gateway.paperProxyStarting(); return "New connections prefer PaperProxy when its local listener accepts connections."; }
          return "Usage: > gateway <status|paperproxy|limbo>";
        }
        case "proxy", "paperproxy", "limbo" -> {
          String targetId = action.equals("proxy") ? "paperproxy" : action;
          int separator = command.indexOf(' ');
          if (separator < 0) return "Usage: > " + action + " <console command>";
          String serviceCommand = command.substring(separator + 1).trim();
          if (serviceCommand.isEmpty()) return "Usage: > " + action + " <console command>";
          ManagedService target = services.get(targetId);
          if (target == null) return "Unknown service: " + targetId;
          long before = target.outputSequence();
          target.sendConsoleLine(serviceCommand);
          scheduleConsoleOutput(target, before, serviceCommand);
          return "Sent command to " + targetId + ": " + serviceCommand + ". Recent output, if emitted promptly, will be appended below.";
        }
        case "update" -> {
          if (parts.length < 2 || !parts[1].equalsIgnoreCase("companion")) return "Usage: > update companion";
          return updateProxyCompanion();
        }
        default -> { return "Unknown manager command. Type '> help' for commands."; }
      }
    } catch (Exception e) { return "Command failed: " + safeError(e); }
  }

  private void scheduleConsoleOutput(ManagedService service, long afterSequence, String command) {
    try {
      managerWorker.schedule(() -> {
        List<String> lines = service.outputAfter(afterSequence);
        String result = lines.isEmpty()
            ? "No new console output was emitted immediately for '" + command + "'. Check the live service console for delayed output."
            : "Recent " + service.id + " output after '" + command + "':\n" + String.join("\n", lines);
        try { appendJournalOutput(result); }
        catch (IOException exception) { System.err.println("[command-file] could not append service output: " + safeError(exception)); }
      }, 700, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException exception) {
      System.err.println("[command-file] could not schedule service output capture: " + safeError(exception));
    }
  }

  private synchronized void appendJournalOutput(String output) throws IOException {
    String safe = output == null ? "(no output)" : output;
    String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Jakarta")).format(Instant.now());
    try (BufferedWriter writer = Files.newBufferedWriter(consoleJournal, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
      for (String line : safe.split("\\R", -1)) {
        writer.write("| [" + timestamp + "] " + line.replaceAll("[\\p{Cntrl}&&[^\\t]]", "?"));
        writer.newLine();
      }
    }
  }

  private String updateProxyCompanion() throws Exception {
    ManagedService paperproxy = services.get("paperproxy");
    if (paperproxy == null) return "PaperProxy service is not configured.";
    boolean wasRunning = paperproxy.isRunning();
    Path pluginDir = root.resolve("paperproxy/plugins").normalize();
    if (!pluginDir.startsWith(root)) throw new IOException("PaperProxy plugins path escaped manager root.");
    Files.createDirectories(pluginDir);
    Path installed = pluginDir.resolve("SuperpowerPlugin.jar");
    Path backup = pluginDir.resolve("SuperpowerPlugin.jar." + System.currentTimeMillis() + ".bak");
    boolean hadPrevious = Files.exists(installed, NOFOLLOW_LINKS);
    if (hadPrevious && (Files.isSymbolicLink(installed) || !Files.isRegularFile(installed, NOFOLLOW_LINKS)))
      throw new IOException("Installed companion JAR must be a regular file, not a symlink.");
    if (hadPrevious) Files.copy(installed, backup, StandardCopyOption.COPY_ATTRIBUTES);

    if (wasRunning) { gatewayIfPresentDrain(); paperproxy.stop(); }
    boolean replaced = false;
    try {
      Path updatesParent = root.resolve(".updates").normalize();
      if (!updatesParent.startsWith(root) || Files.isSymbolicLink(updatesParent)) throw new IOException("Update workspace path is unsafe.");
      Files.createDirectories(updatesParent);
      Path updateRoot = updatesParent.resolve("universal-plugin").normalize();
      if (!updateRoot.startsWith(root) || Files.isSymbolicLink(updateRoot)) throw new IOException("Update workspace must be a real directory inside manager root.");
      deleteTree(updateRoot);

      String branch = setting("anobank.manager.companion.branch", "feature/superpower-plugin");
      if (!branch.matches("[A-Za-z0-9._/-]{1,80}") || branch.contains(".."))
        throw new IllegalArgumentException("anobank.manager.companion.branch is invalid.");
      ProcessResult clone = runLimited(List.of("git", "clone", "--depth", "1", "--branch", branch,
          "https://github.com/DewanTeam/capture-c.git", updateRoot.toString()), root, Duration.ofMinutes(3));
      if (clone.exitCode != 0) throw new IOException("Git clone failed: " + tail(clone.output, 4000));
      ProcessResult build = runLimited(List.of("./gradlew", "--no-daemon", "clean", "build"), updateRoot, Duration.ofMinutes(8));
      if (build.exitCode != 0) throw new IOException("Universal plugin build failed: " + tail(build.output, 6000));
      Path candidate = updateRoot.resolve("build/libs/SuperpowerPlugin.jar");
      verifyUniversalPluginJar(candidate);

      Path staged = pluginDir.resolve("SuperpowerPlugin.jar.update.tmp");
      Files.copy(candidate, staged, StandardCopyOption.REPLACE_EXISTING);
      atomicReplace(staged, installed);
      replaced = true;

      if (wasRunning) paperproxy.start(null, null);
      return "Updated PaperProxy companion from DewanTeam/capture-c branch '" + branch
          + "'. Build output: " + tail(build.output, 1600);
    } catch (Exception failure) {
      if (replaced) {
        try {
          Path staged = pluginDir.resolve("SuperpowerPlugin.jar.rollback.tmp");
          if (hadPrevious) {
            Files.copy(backup, staged, StandardCopyOption.REPLACE_EXISTING);
            atomicReplace(staged, installed);
          } else {
            Files.deleteIfExists(installed);
          }
        } catch (Exception rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
      }
      if (wasRunning && !paperproxy.isRunning()) {
        try { paperproxy.start(null, null); }
        catch (Exception restartFailure) { failure.addSuppressed(restartFailure); }
      }
      throw failure;
    }
  }

  private void gatewayIfPresentDrain() { if (gateway != null) gateway.paperProxyStopping(); }

  private static void deleteTree(Path directory) throws IOException {
    if (Files.notExists(directory, NOFOLLOW_LINKS)) return;
    if (Files.isSymbolicLink(directory)) throw new IOException("Refusing to delete a symlinked update directory.");
    Files.walkFileTree(directory, new SimpleFileVisitor<>() {
      @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (attrs.isSymbolicLink() || Files.isSymbolicLink(file)) throw new IOException("Refusing to delete symlink inside update workspace: " + file.getFileName());
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }
      @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
        if (error != null) throw error;
        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static ProcessResult runLimited(List<String> command, Path cwd, Duration timeout) throws Exception {
    Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Thread reader = new Thread(() -> { try (InputStream in = process.getInputStream()) { in.transferTo(new LimitedOutputStream(output, 64 * 1024)); } catch (IOException ignored) {} }, "manager-update-output");
    reader.setDaemon(true); reader.start();
    if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { process.destroyForcibly(); throw new TimeoutException("Command timed out: " + command.get(0)); }
    reader.join(2000);
    return new ProcessResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
  }

  private static String tail(String value, int max) { return value.length() <= max ? value : value.substring(value.length() - max); }
  private static void verifyUniversalPluginJar(Path jar) throws IOException {
    if (!Files.isRegularFile(jar, NOFOLLOW_LINKS) || Files.isSymbolicLink(jar)) throw new IOException("Built companion JAR is missing or unsafe.");
    try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile())) {
      if (file.getEntry("paper-plugin.yml") == null || file.getEntry("velocity-plugin.json") == null)
        throw new IOException("Build output is not a universal Paper + Velocity plugin JAR.");
    }
  }

  private record OutputLine(long sequence, String text) {}
  private record ProcessResult(int exitCode, String output) {}
  private static final class LimitedOutputStream extends OutputStream {
    private final ByteArrayOutputStream delegate; private final int limit;
    LimitedOutputStream(ByteArrayOutputStream delegate, int limit) { this.delegate = delegate; this.limit = limit; }
    @Override public void write(int b) { if (delegate.size() < limit) delegate.write(b); }
    @Override public void write(byte[] b, int off, int len) { int remaining = limit - delegate.size(); if (remaining > 0) delegate.write(b, off, Math.min(len, remaining)); }
  }

  /** Delete stale rotated log files, but never follow symlinks or unlink latest.log. */
  private void cleanupOldLogsSafely() {
    try { cleanupOldLogs(); }
    catch (Exception e) { System.err.println("[log-retention] cleanup failed: " + safeError(e)); }
  }

  private void cleanupOldLogs() throws IOException {
    Instant cutoff = Instant.now().minus(Duration.ofHours(logRetentionHours));
    for (Path logsDir : List.of(root.resolve("paperproxy").resolve("logs"), root.resolve("limbo").resolve("logs"))) {
      if (!Files.exists(logsDir, NOFOLLOW_LINKS)) continue;
      if (Files.isSymbolicLink(logsDir) || !Files.isDirectory(logsDir, NOFOLLOW_LINKS)) {
        System.err.println("[log-retention] ignoring non-directory/symlink log path: " + logsDir);
        continue;
      }
      Files.walkFileTree(logsDir, new SimpleFileVisitor<>() {
        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          try {
            if (attrs.isRegularFile() && !Files.isSymbolicLink(file)
                && !file.getFileName().toString().equalsIgnoreCase("latest.log")
                && Files.getLastModifiedTime(file, NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
              Files.deleteIfExists(file);
              System.out.println("[log-retention] deleted stale log " + root.relativize(file) + " (older than " + logRetentionHours + " hours)");
            }
          } catch (Exception e) { System.err.println("[log-retention] could not delete " + file.getFileName() + ": " + safeError(e)); }
          return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
          if (exc != null) System.err.println("[log-retention] could not scan directory " + dir.getFileName() + ": " + safeError(exc));
          // Keep the service's top-level logs/ directory. Only prune empty nested folders.
          if (!dir.equals(logsDir)) {
            try (var children = Files.list(dir)) {
              if (children.findAny().isEmpty()) Files.deleteIfExists(dir);
            } catch (Exception ignored) {}
          }
          return FileVisitResult.CONTINUE;
        }
      });
    }
  }

  private static List<String> validateArgs(List<String> args) {
    if (args == null) return null;
    if (args.size() > MAX_ARGS) throw new IllegalArgumentException("At most " + MAX_ARGS + " application arguments are allowed.");
    List<String> copy = new ArrayList<>();
    for (String arg : args) { validateArg(arg); copy.add(arg); }
    return List.copyOf(copy);
  }
  private static void validateArg(String arg) {
    if (arg == null || arg.length() > MAX_ARG_LENGTH || arg.indexOf('\0') >= 0 || arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0) throw new IllegalArgumentException("Invalid argument value.");
  }
  private static List<String> validateJvmArgs(List<String> args) {
    if (args == null) return null;
    if (args.size() > MAX_ARGS) throw new IllegalArgumentException("At most " + MAX_ARGS + " JVM override arguments are allowed.");
    Set<String> booleanFlags = Set.of("UseG1GC", "UseZGC", "UseSerialGC", "ParallelRefProcEnabled", "AlwaysPreTouch", "UnlockExperimentalVMOptions", "UseStringDeduplication", "DisableExplicitGC");
    Set<String> numericFlags = Set.of("G1HeapRegionSize", "MaxInlineLevel", "MaxGCPauseMillis", "ParallelGCThreads", "ConcGCThreads", "MaxRAMPercentage", "InitialRAMPercentage", "MetaspaceSize", "MaxMetaspaceSize", "ReservedCodeCacheSize");
    List<String> result = new ArrayList<>();
    for (String arg : args) {
      validateArg(arg);
      boolean valid = arg.matches("(?i)-X(?:ms|mx|ss)[1-9][0-9]*(?:[kmg])?") || arg.equals("-ea") || arg.equals("-da") ||
          arg.matches("-D[A-Za-z0-9_.-]{1,100}=[A-Za-z0-9_./:+-]{1,200}");
      Matcher bool = Pattern.compile("-XX:([+-])([A-Za-z][A-Za-z0-9]+)").matcher(arg);
      if (bool.matches() && booleanFlags.contains(bool.group(2))) valid = true;
      Matcher numeric = Pattern.compile("-XX:([A-Za-z][A-Za-z0-9]+)=([A-Za-z0-9.]+)").matcher(arg);
      if (numeric.matches() && numericFlags.contains(numeric.group(1)) && numeric.group(2).matches("[1-9][0-9]*(?:\\.[0-9]+)?[kKmMgG]?")) valid = true;
      if (!valid) throw new IllegalArgumentException("JVM override is not in the allowed safe subset: " + arg);
      result.add(arg);
    }
    return List.copyOf(result);
  }

  private void loadServerPropertiesReadOnly() throws IOException {
    Path file = root.resolve("server.properties");
    if (Files.notExists(file)) return;
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, NOFOLLOW_LINKS))
      throw new IOException("server.properties must be a regular file; it is read only.");
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      serverProperties.load(reader);
    }
  }

  private String setting(String name, String fallback) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) value = serverProperties.getProperty(name);
    if (value == null || value.isBlank()) value = System.getenv(name.toUpperCase(Locale.ROOT).replace('.', '_'));
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private int intSetting(String name, int fallback, int min, int max) {
    String value = setting(name, null);
    if (value == null) return fallback;
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < min || parsed > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ".");
      return parsed;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " must be an integer.", e);
    }
  }

  private int gatewayPort() {
    String configured = setting("anobank.manager.gateway.port", System.getenv("SERVER_PORT"));
    if (configured == null || configured.isBlank()) return 0;
    try {
      int port = Integer.parseInt(configured.trim());
      if (port == 0) return 0;
      if (port < 1 || port > 65535) throw new IllegalArgumentException("Gateway public port must be between 1 and 65535.");
      return port;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Gateway public port must be an integer.", exception);
    }
  }

  private boolean booleanSetting(String name, boolean fallback) {
    String value = setting(name, null);
    if (value == null) return fallback;
    if (value.equalsIgnoreCase("true")) return true;
    if (value.equalsIgnoreCase("false")) return false;
    throw new IllegalArgumentException(name + " must be true or false.");
  }

  private String heapSetting(String name, String fallback) {
    String value = setting(name, fallback);
    if (!value.matches("[1-9][0-9]*(?:[kKmMgG])?"))
      throw new IllegalArgumentException(name + " must be a heap size such as 128M or 1G.");
    return value;
  }

  private List<String> csvSetting(String name, String fallback) {
    String value = setting(name, fallback);
    if (value == null || value.isBlank()) return List.of();
    List<String> result = new ArrayList<>();
    for (String entry : value.split(",")) if (!entry.isBlank()) result.add(entry.trim());
    return List.copyOf(result);
  }

  private static String safeError(Throwable e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) message = e.getClass().getSimpleName();
    message = message.replaceAll("https?://\\S+", "[redacted-url]").replaceAll("(?i)(token|secret|password)=([^&\\s]+)", "$1=[redacted]");
    return message.length() > 500 ? message.substring(0, 500) : message;
  }
  private void shutdown() {
    if (!shutdownStarted.compareAndSet(false, true)) return;
    System.out.println("[manager] stopping background manager worker...");
    managerWorker.shutdownNow();
    maintenanceWorker.shutdownNow();
    // LinkedHashMap keeps PaperProxy first, then Limbo. PaperProxy receives its native
    // `end` command; Limbo receives its built-in `stop` command, with process termination
    // used only as a fallback if either process fails to exit.
    for (ManagedService s : services.values()) {
      System.out.println("[manager] stopping managed service " + s.id + "...");
      try { s.stop(); }
      catch (Exception e) { System.err.println("[manager] shutdown error for " + s.id + ": " + safeError(e)); }
      System.out.println("[manager] stop routine returned for " + s.id + ".");
    }
    if (gateway != null) gateway.close();
    System.out.println("[manager] shutdown complete.");
  }

  private static String sha256(Path p) throws Exception {
    MessageDigest d=MessageDigest.getInstance("SHA-256");
    try(InputStream in=Files.newInputStream(p)) { byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1)d.update(b,0,n); }
    return HexFormat.of().formatHex(d.digest());
  }
  private static void verifyJar(Path p) throws IOException {
    try (java.util.jar.JarFile jf = new java.util.jar.JarFile(p.toFile())) {
      java.util.jar.Manifest manifest = jf.getManifest();
      String mainClass = manifest == null ? null : manifest.getMainAttributes().getValue(java.util.jar.Attributes.Name.MAIN_CLASS);
      if (jf.size() == 0 || mainClass == null || mainClass.isBlank()) throw new IOException("File is not an executable JAR with a Main-Class manifest entry.");
    }
  }
  private static void atomicReplace(Path src, Path dest) throws IOException {
    try { Files.move(src,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
    catch (AtomicMoveNotSupportedException e) { Files.move(src,dest,StandardCopyOption.REPLACE_EXISTING); }
  }

  private final class ManagedService {
    final String id; final Path jar, cwd; final String xms, xmx; final List<String> jvmArgs; final Path startScript; final List<String> defaultArgs; final boolean autoRestart;
    final Deque<OutputLine> recentOutput = new ArrayDeque<>();
    long outputSequence;
    Process process; BufferedWriter consoleWriter; Instant startedAt; String lastError = ""; List<String> lastArgs; List<String> lastJvmOverrides = List.of();
    boolean desiredRunning; boolean restartRequestedByConsole; volatile long intentionalRestartUntilMillis; final Deque<Long> unexpectedRestarts = new ArrayDeque<>();
    ManagedService(String id, Path jar, Path cwd, String xms, String xmx, List<String> jvmArgs, List<String> defaultArgs, boolean autoRestart) throws IOException {
      this.id=id; this.jar=jar; this.cwd=cwd; this.xms=xms; this.xmx=xmx; this.jvmArgs=jvmArgs; this.defaultArgs=defaultArgs; this.autoRestart=autoRestart;
      this.lastArgs = defaultArgs;
      if ("paperproxy".equals(id)) {
        this.startScript = cwd.resolve("start.sh").toAbsolutePath().normalize();
        if (!this.startScript.startsWith(cwd)) throw new IOException("PaperProxy start.sh must be inside the paperproxy working directory.");
        ensurePaperProxyStartScript();
      } else this.startScript = null;
    }

    private void ensurePaperProxyStartScript() throws IOException {
      if (Files.exists(startScript, NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(startScript) || !Files.isRegularFile(startScript, NOFOLLOW_LINKS))
          throw new IOException("paperproxy/start.sh must be a regular file and must not be a symlink.");
        // Migrate only a recognizable default script created by this manager. Custom scripts
        // are never rewritten; admins retain control of their JVM flags.
        String existing = Files.readString(startScript, StandardCharsets.UTF_8);
        boolean generatedDefault = existing.contains("# Executed with /home/container/paperproxy as the working directory.")
            && existing.contains("-jar ../paperproxy.jar \"$@\"")
            && existing.contains("-Xms1G -Xmx1G -XX:+UseG1GC");
        if (generatedDefault) {
          List<String> missing = new ArrayList<>();
          if (!existing.contains("-Dterminal.jline=false")) missing.add("-Dterminal.jline=false");
          if (!existing.contains("-Dterminal.ansi=true")) missing.add("-Dterminal.ansi=true");
          if (!existing.contains("-Duser.timezone=Asia/Jakarta")) missing.add("-Duser.timezone=Asia/Jakarta");
          if (!missing.isEmpty()) {
            String migrated = existing.replaceFirst("(?m)^exec java ", Matcher.quoteReplacement("exec java " + String.join(" ", missing) + " "));
            Path temp = startScript.resolveSibling("start.sh.migrate.tmp");
            Files.deleteIfExists(temp);
            Files.writeString(temp, migrated, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try { Files.move(temp, startScript, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, startScript, StandardCopyOption.REPLACE_EXISTING); }
            System.out.println("[manager] updated the previous manager-generated start.sh with missing console/timezone flags; custom scripts were not modified.");
          }
        }
        return; // Preserve administrator edits; only the known prior default is migrated.
      }
      String launch = "#!/bin/sh\n" +
          "set -eu\n\n" +
          "# Executed with /home/container/paperproxy as the working directory.\n" +
          "# The JAR stays in /home/container; edit JVM flags on this line.\n" +
          "exec java -Dterminal.jline=false -Dterminal.ansi=true -Duser.timezone=Asia/Jakarta -Xms1G -Xmx1G -XX:+UseG1GC -XX:G1HeapRegionSize=4M -XX:+UnlockExperimentalVMOptions -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch -XX:MaxInlineLevel=15 -jar ../paperproxy.jar \"$@\"\n";
      try {
        Files.writeString(startScript, launch, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try { Files.setPosixFilePermissions(startScript, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---")); }
        catch (UnsupportedOperationException ignored) { /* It is invoked with /bin/sh. */ }
        System.out.println("[manager] created " + startScript + "; edit its java line to change PaperProxy JVM flags.");
      } catch (FileAlreadyExistsException ignored) {
        if (Files.isSymbolicLink(startScript) || !Files.isRegularFile(startScript, NOFOLLOW_LINKS)) throw new IOException("paperproxy/start.sh must be a regular file and must not be a symlink.");
      }
    }

    synchronized ManagedService start(List<String> requestedArgs, List<String> requestedJvmArgs) throws IOException {
      if (process != null && process.isAlive()) { desiredRunning = true; return this; }
      if (!"paperproxy".equals(id) && (!Files.isRegularFile(jar, NOFOLLOW_LINKS) || Files.isSymbolicLink(jar)))
        throw new IllegalArgumentException("Configured JAR does not exist or is a symlink for " + id + ": " + jar.getFileName());
      if (requestedArgs != null) lastArgs = validateArgs(requestedArgs);
      if ("paperproxy".equals(id)) {
        if (requestedJvmArgs != null && !requestedJvmArgs.isEmpty())
          throw new IllegalArgumentException("PaperProxy JVM flags belong in paperproxy/start.sh; the file contents are authoritative.");
      } else if (requestedJvmArgs != null) {
        lastJvmOverrides = validateJvmArgs(requestedJvmArgs);
      }

      List<String> command = new ArrayList<>();
      if ("paperproxy".equals(id)) {
        command.add("/bin/sh"); command.add(startScript.toAbsolutePath().toString()); command.addAll(lastArgs);
      } else {
        command.add("java");
        command.add("-Xms" + xms); command.add("-Xmx" + xmx); command.add("-XX:+UseG1GC"); command.addAll(jvmArgs); command.addAll(lastJvmOverrides);
        command.add("-jar"); command.add("../" + jar.getFileName().toString()); command.addAll(lastArgs);
      }
      ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
      pb.environment().put("ANOBANK_MANAGED_SERVICE", id);
      process = pb.start(); desiredRunning = true; restartRequestedByConsole = false; consoleWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)); startedAt = Instant.now(); lastError = "";
      if ("paperproxy".equals(id) && gateway != null) gateway.paperProxyStarting();
      Process current = process;
      Thread output = new Thread(() -> {
        try (InputStream stream = current.getInputStream()) {
          if ("paperproxy".equals(id)) {
            // Keep PaperProxy output byte-for-byte: ANSI escapes, carriage returns and colors survive.
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream currentLine = new ByteArrayOutputStream();
            int count;
            while ((count = stream.read(buffer)) != -1) {
              System.out.write(buffer, 0, count);
              System.out.flush();
              for (int i = 0; i < count; i++) {
                int value = buffer[i] & 0xFF;
                if (value == '\n') {
                  recordOutput(currentLine.toString(StandardCharsets.UTF_8));
                  currentLine.reset();
                } else if (currentLine.size() < 8192 && value != '\r') {
                  currentLine.write(value);
                }
              }
            }
            if (currentLine.size() > 0) recordOutput(currentLine.toString(StandardCharsets.UTF_8));
          } else {
            // Keep Limbo's existing prefixed, line-oriented output unchanged for now.
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
              String line; while ((line = r.readLine()) != null) { System.out.println("[" + id + "] " + line); recordOutput(line); }
            }
          }
        } catch (IOException e) {
          if (current.isAlive()) System.err.println("[manager] " + id + " output read error: " + safeError(e));
        }
      }, "managed-" + id + "-output");
      output.setDaemon(true); output.start();
      Thread watcher = new Thread(() -> watchExit(current), "managed-" + id + "-watcher");
      watcher.setDaemon(true); watcher.start();
      System.out.println("[manager] started " + id + " in " + cwd + ".");
      return this;
    }

    private void watchExit(Process watched) {
      int exitCode;
      try { exitCode = watched.waitFor(); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
      boolean restart = false;
      long delaySeconds = 1;
      synchronized (this) {
        if (process != watched) return;
        process = null;
        if ("paperproxy".equals(id) && gateway != null) gateway.paperProxyStopping();
        try { if (consoleWriter != null) consoleWriter.close(); } catch (IOException ignored) {}
        consoleWriter = null;
        System.out.println("[manager] " + id + " exited with code " + exitCode + ".");
        if (desiredRunning && autoRestart && !shutdownStarted.get()) {
          boolean requested = restartRequestedByConsole;
          boolean planned = System.currentTimeMillis() <= intentionalRestartUntilMillis;
          restartRequestedByConsole = false;
          intentionalRestartUntilMillis = 0;
          if (requested || planned || exitCode == 0) {
            restart = true; delaySeconds = 1;
            System.out.println("[manager] " + id + " exited cleanly after a planned/normal shutdown; starting it again in 1 second.");
          } else {
            long now = System.currentTimeMillis();
            while (!unexpectedRestarts.isEmpty() && now - unexpectedRestarts.peekFirst() > TimeUnit.MINUTES.toMillis(10)) unexpectedRestarts.removeFirst();
            if (unexpectedRestarts.size() < 5) {
              unexpectedRestarts.addLast(now);
              delaySeconds = Math.min(30, 1L << Math.min(5, unexpectedRestarts.size() - 1));
              restart = true;
              System.out.println("[manager] " + id + " exited unexpectedly; restart attempt " + unexpectedRestarts.size() + "/5 in " + delaySeconds + " second(s).");
            } else {
              desiredRunning = false;
              lastError = "Automatic restart paused after 5 automatic restart attempts within 10 minutes. Use !manager start " + id + " after fixing the cause.";
              System.err.println("[manager] " + lastError);
            }
          }
        }
      }
      if (restart && !shutdownStarted.get()) {
        long wait = delaySeconds;
        try {
          managerWorker.schedule(() -> {
            synchronized (ManagedService.this) {
              if (!desiredRunning || process != null || shutdownStarted.get()) return;
              try { start(null, null); }
              catch (Exception e) { lastError = safeError(e); System.err.println("[manager] could not restart " + id + ": " + lastError); }
            }
          }, wait, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
          if (!shutdownStarted.get()) System.err.println("[manager] restart skipped because the task queue is closing.");
        }
      }
    }

    private String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    synchronized void recordOutput(String rawLine) {
      if (rawLine == null) return;
      String clean = rawLine.replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "")
          .replaceAll("[\\p{Cntrl}&&[^\\t]]", "?");
      if (clean.isBlank()) return;
      outputSequence++;
      recentOutput.addLast(new OutputLine(outputSequence, clean.length() > 1200 ? clean.substring(0, 1200) : clean));
      while (recentOutput.size() > 200) recentOutput.removeFirst();
    }

    synchronized long outputSequence() { return outputSequence; }

    synchronized List<String> outputAfter(long sequence) {
      return recentOutput.stream().filter(line -> line.sequence > sequence).map(OutputLine::text).toList();
    }

    synchronized void sendConsoleLine(String command) throws IOException {
      if (command == null || command.isBlank() || command.length() > MAX_ARG_LENGTH || command.indexOf('\0') >= 0 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) throw new IllegalArgumentException("Console command must be one non-empty line of at most " + MAX_ARG_LENGTH + " characters.");
      if (process == null || !process.isAlive() || consoleWriter == null) throw new IllegalStateException(id + " is not running; command was not sent.");
      if ("paperproxy".equals(id)) {
        String normalized = command.trim().replaceFirst("^/", "");
        if (normalized.equalsIgnoreCase("end")) {
          restartRequestedByConsole = true;
          if (gateway != null) gateway.paperProxyStopping();
        } else if (normalized.matches("(?i)(paperproxy|pp)\\s+restart\\s+cancel")) {
          intentionalRestartUntilMillis = 0;
          if (gateway != null && process != null && process.isAlive()) gateway.paperProxyStarting();
        } else {
          Matcher restartCommand = Pattern.compile("(?i)^(?:paperproxy|pp)\\s+restart\\s+([0-9]{1,5})(?:\\s+.*)?$").matcher(normalized);
          if (restartCommand.matches()) {
            long seconds = Long.parseLong(restartCommand.group(1));
            if (seconds >= 1 && seconds <= 86400) {
              intentionalRestartUntilMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds + 45);
              if (gateway != null) gateway.paperProxyStopping();
            }
          }
        }
      }
      consoleWriter.write(command); consoleWriter.newLine(); consoleWriter.flush();
    }

    synchronized ManagedService restart(List<String> args, List<String> jvmArgs) throws IOException { stop(); return start(args, jvmArgs); }
    synchronized ManagedService stop() {
      desiredRunning = false;
      restartRequestedByConsole = false;
      Process current = process;
      if (current != null && current.isAlive()) {
        if ("paperproxy".equals(id)) {
          try {
            sendConsoleLine("end");
            System.out.println("[manager] sent PaperProxy 'end'; waiting up to 12 seconds for graceful shutdown.");
          } catch (Exception e) {
            System.err.println("[manager] could not send PaperProxy 'end': " + safeError(e));
          }
          try {
            if (!current.waitFor(12, TimeUnit.SECONDS) && current.isAlive()) {
              System.err.println("[manager] PaperProxy did not exit after 'end'; sending normal process termination.");
              current.destroy();
            }
          } catch (InterruptedException e) { Thread.currentThread().interrupt(); current.destroy(); }
        } else {
          try {
            sendConsoleLine("stop");
            System.out.println("[manager] sent Limbo's built-in 'stop' command; waiting for its System.exit shutdown.");
            if (!current.waitFor(8, TimeUnit.SECONDS) && current.isAlive()) {
              System.err.println("[manager] Limbo did not exit after 'stop'; requesting normal process termination.");
              current.destroy();
            }
          } catch (Exception e) {
            System.err.println("[manager] could not send Limbo 'stop': " + safeError(e));
            current.destroy();
          }
        }
        try {
          if (!current.waitFor(4, TimeUnit.SECONDS) && current.isAlive()) {
            System.err.println("[manager] " + id + " is still alive; force-stopping as a last resort.");
            current.destroyForcibly();
            current.waitFor(2, TimeUnit.SECONDS);
          }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); current.destroyForcibly(); }
      }
      try { if (consoleWriter != null) consoleWriter.close(); } catch (IOException ignored) {}
      consoleWriter = null;
      process = null; return this;
    }

    synchronized boolean isRunning() { return process != null && process.isAlive(); }

    synchronized ManagedService rollback() throws IOException {
      Path backup = latestBackup(); if (backup == null) throw new IllegalArgumentException("No PaperProxy backup is available.");
      stop(); Path staged = jar.resolveSibling(jar.getFileName()+".rollback.tmp");
      Files.copy(backup, staged, StandardCopyOption.REPLACE_EXISTING); verifyJar(staged); atomicReplace(staged, jar); return this;
    }

    synchronized ManagedService update(String urlText, String expectedSha) throws Exception {
      if (!"paperproxy".equals(id)) throw new IllegalArgumentException("Updates are only allowed for PaperProxy.");
      if (process != null && process.isAlive()) throw new IllegalArgumentException("Stop PaperProxy before updating it.");
      URI uri = URI.create(urlText);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("Update URL must be an HTTPS URL without embedded credentials.");
      if (!expectedSha.matches("(?i)[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 must be 64 hexadecimal characters.");
      Path staging = jar.resolveSibling(jar.getFileName()+".download.tmp");
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
      HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).header("User-Agent", "ANOBank-PaperProxy-Manager/3.3.0").GET().build();
      HttpResponse<InputStream> response = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) { response.body().close(); throw new IOException("Update server returned HTTP " + response.statusCode()); }
      long total = 0;
      try (InputStream in=response.body(); OutputStream out=Files.newOutputStream(staging, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
        byte[] buf=new byte[8192]; int n; while((n=in.read(buf))!=-1) { total+=n; if(total > 512L*1024*1024) throw new IOException("Update exceeds 512 MiB limit."); out.write(buf,0,n); }
      } catch (Exception e) { Files.deleteIfExists(staging); throw e; }
      String actual = sha256(staging);
      if (!actual.equalsIgnoreCase(expectedSha)) { Files.deleteIfExists(staging); throw new IllegalArgumentException("SHA-256 verification failed; current JAR was not changed."); }
      verifyJar(staging);
      if (Files.exists(jar)) { Path backupDir = cwd.resolve("backups"); Files.createDirectories(backupDir); Path backup = backupDir.resolve(jar.getFileName()+"."+System.currentTimeMillis()+".bak"); Files.copy(jar, backup); }
      atomicReplace(staging, jar); return this;
    }

    private Path latestBackup() throws IOException {
      Path dir=cwd.resolve("backups"); if(!Files.isDirectory(dir)) return null;
      try(var stream=Files.list(dir)) { return stream.filter(p->p.getFileName().toString().startsWith(jar.getFileName()+".") && p.toString().endsWith(".bak") && Files.isRegularFile(p, NOFOLLOW_LINKS)).max(Comparator.comparingLong(p->p.toFile().lastModified())).orElse(null); }
    }
    synchronized String statusJson() {
      boolean running=process != null && process.isAlive(); long pid=running?process.pid():-1;      return "{\"id\":"+quote(id)+",\"running\":"+running+",\"desiredRunning\":"+desiredRunning+",\"autoRestart\":"+autoRestart+",\"pid\":"+(running?pid:"null")+",\"jar\":"+quote(jar.getFileName().toString())+",\"cwd\":"+quote(cwd.toString())+",\"startedAt\":"+(startedAt==null?"null":quote(startedAt.toString()))+",\"lastError\":"+quote(lastError)+"}";
    }
  }
}