# Voxy for Forge 1.19.2

This branch is a functional client-side backport of Voxy 0.2.14-alpha to Minecraft 1.19.2. It keeps the real terrain renderer, model bakery, chunk ingestion, level-of-detail generation, and persistent section storage; it is not the earlier log-only renderer stub.

## Runtime requirements

- Minecraft 1.19.2
- Forge 43.5.2 (the version used by the validation suite)
- Java 17
- Embeddium `0.3.32-beta.90+mc1.19.2`
- an OpenGL 4.6 / GLSL 4.60-capable implementation

Voxy is client-side. The server does not need the mod.

## Build

```bash
./gradlew --no-daemon clean test build jarJar
```

Install the generated `build/libs/*-all.jar`. The ordinary slim JAR does not contain the complete embedded runtime dependency set.

## Validation

The branch has two independent CI contracts:

1. Java 17 unit/parity tests, Forge compilation, reobfuscation, package metadata checks, and distributable-JAR creation.
2. An installed-artifact test which boots the packaged JAR, reaches the title screen, joins a separate clean Forge server, creates the live Voxy renderer, completes terrain render passes, writes section data, exits fully, launches again, and reads the same persisted sections.

The runtime contract also verifies that direct-connect multiplayer worlds use a stable server-specific storage directory rather than the shared legacy `UNKNOWN` path.

## Compatibility boundary

The core Forge 1.19.2 renderer path and Embeddium integration are included. Oculus/Iris shader-pack integration is intentionally excluded because the public Minecraft 1.19.2 Oculus line exposes an older API than the Voxy baseline. The automated contract proves the clean tested configuration; it does not claim compatibility with every modpack or graphics driver.

See `PORTING.md` for the port contract and `FUNCTIONAL_BASELINE.md` for the selected upstream baseline.
