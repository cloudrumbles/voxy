# Functional backport baseline

The rendering, meshing, model-baking, world-ingest, persistence, and
shader source tree on this branch comes from Voxy 0.2.14-alpha branch
`m3t4f1v3/voxy:mc_1201-java17-3.3.1`.

That branch is the last known Java 17 / Minecraft 1.20.1 lineage with
the complete direct-LWJGL renderer and is also the source used by the
independently working Boxy Forge 1.20.1 compatibility project.

The earlier `guchang233/voxy-forge-1.20.1` import was rejected as a
behavioural baseline because it replaced Voxy's renderer with a log-only
stub. Its final state is retained only on `forge-1.19.2-stub-reference`.

Loader integration is being rewritten for Forge 43/Minecraft 1.19.2;
stable core formats and algorithms are guarded by the regression suite.
