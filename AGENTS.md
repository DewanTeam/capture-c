# AGENTS.md

## Layout

Two independent Java 21 projects in one repo. Always build from inside their directories.

- `mod/` — Fabric client mod ("Cyber Capture", mod id `recordable`, MC 1.21.11 + Yarn) that captures the game framebuffer into a custom `.cyber` archive. Gradle 9.5.1 wrapper, Fabric Loom.
- `renderer/` — standalone Maven CLI app (`dev.recordable.OfflineRenderer`) that decodes `.cyber` archives into MP4 via an `ffmpeg` subprocess.

## Commands

```bash
# Mod — build is the only verification (no tests exist)
(cd mod && ./gradlew build)       # jar -> mod/build/libs/
(cd mod && ./gradlew runClient)   # test instance, game dir = mod/run/

# Renderer — needs Maven to build, ffmpeg on PATH to run
(cd renderer && mvn package)
java -jar renderer/target/cyber-renderer-1.0-jar-with-dependencies.jar capture.cyber
```

## Gotchas

- CI (`.github/workflows/build.yml`) runs `./gradlew build` at repo root, but the Gradle wrapper lives in `mod/` — CI is broken since the project moved out of the repo root.
- The built jar is `modid-1.0.0.jar`, not `cyber-capture`: `settings.gradle` sets `rootProject.name = 'modid'` and `archives_base_name` in `gradle.properties` is never referenced by `build.gradle`.
- Unused template leftovers: `mod/src/main/resources/modid.mixins.json` (points at nonexistent `com.example.mixin`) and `assets/modid/icon.png`. The real mixin config is `recordable.mixins.json`.
- Renderer speed factor is hardcoded as `20 / tickRate`; output is forced to 60 FPS with libx264.

## Shared `.cyber` binary format

Written by `mod/src/main/java/net/dewanteam/recordablelite/FrameArchiveWriter.java`, read by `renderer/src/main/java/dev/recordable/FrameArchiveReader.java`. Magic `0x43594252` ("CYBR"), version int, header (width, height, captureFps, targetFps, tickRate), frameCount placeholder patched back on close. Any format change must be mirrored in both files or archives silently break.

## Mod behavior

- F9 toggles recording; polled via GLFW in `GameRendererMixin` at `GameRenderer.render` TAIL, which also calls `RecordingManager.onFrame()` every frame for time-gated capture.
- Config `recordable-lite.properties` in the game config dir: `fps` clamped to 1–30 (default 3), `outputDirectory` default `captures` (relative to game dir). File is rewritten on every load.
- Client-only mod depending on Fabric Loader only — no Fabric API.
