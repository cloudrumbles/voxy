# Voxy Forge 1.19.2 port contract

This branch targets Minecraft 1.19.2, Forge 43.5.2, Java 17, and Embeddium `0.3.32-beta.90+mc1.19.2`. Loader-specific integration is adapted to Forge while persistent world formats, coordinate packing, voxel/mip layouts, configuration defaults, and core rendering behaviour remain aligned with the selected upstream baseline.

The branch is source-only and preserves the upstream licence.

## Completed validation gates

The port is considered complete only when all of these gates pass on the same branch head:

1. Java 17 regression tests cover coordinate packing, section layouts, hierarchical bit sets, bit operations, and multiplayer storage-key isolation.
2. The complete renderer compiles and reobfuscates against Forge 1.19.2 and the selected Embeddium renderer API.
3. The distributable JarJar artifact contains Forge metadata, both Voxy mixin configurations, the generated refmap, the access transformer, and its embedded runtime dependencies.
4. A source-less ForgeGradle client loads the installed packaged artifact and reaches the title screen with Voxy and Embeddium initialized.
5. That client joins a separate clean Forge server, creates the live Voxy render system, completes terrain passes, and writes non-empty section persistence.
6. A second client process reopens the same server-specific storage path and successfully deserializes persisted Voxy sections.
7. The direct-connect test rejects the shared legacy `UNKNOWN` storage directory and requires the expected host-and-port key.

## Intentional compatibility boundary

The functional renderer, storage engine, model bakery, world ingestion, Forge lifecycle, client commands, and Embeddium integration are present. Oculus/Iris integration is excluded because Minecraft 1.19.2 Oculus is based on an older shader API than the Voxy baseline. A clean Forge server requires no Voxy installation.

The runtime suite is deliberately stricter than a title-screen smoke test, but it is not a substitute for broad hardware and modpack testing. Unsupported optional integrations should fail independently rather than weakening the validated core path.
