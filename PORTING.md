# Voxy Forge 1.19.2 port contract

This branch targets Minecraft 1.19.2, Forge 43.5.2, and Java 17.
Loader-specific implementation details may change, while persistent
world formats, coordinate packing, voxel/mip layouts, configuration
defaults, and lifecycle behaviour remain compatible with upstream.

The branch is source-only and preserves the upstream licence.

## Validation gates

The port is not considered complete until all of the following hold:

1. the Java 17 parity and data-layout test suite passes;
2. the complete functional renderer compiles against Forge 1.19.2 and the
   modernized Embeddium renderer API;
3. the distributable JAR contains its mixin refmap, access transformer, and
   required native libraries;
4. an automated client smoke test reaches the title screen and loads a test
   world without mixin, linkage, or renderer-initialization failures;
5. the same fixture world produces the expected Voxy section data and stable
   persistence output across restart.

Current migration boundary: native Forge bootstrap, client commands, Embeddium
configuration integration, renderer lifecycle, and chunk-ingest hooks are in
place. The next compiler pass is used to identify only the remaining Minecraft
1.20.1-to-1.19.2 API adaptations and optional-integration seams.
