package me.cortex.voxy.client.mixin.minecraft;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Replaces the synchronized-wrapper around visibleSections in the light section
// storage constructor with the raw map.
//
// WHY: voxy worker threads call light.getDataLayerData(pos) from
// VoxelIngestService, VoxyDistantGenSaveService, and the block-update ingest in
// MixinClientLevel. Each call ultimately reads visibleSections. With the
// synchronize wrapper, every read takes the map's monitor. Under
// c2me-threading-lighting (which routes vanilla's ServerLightingProvider onto
// a dedicated per-dimension light thread), the writer holds the monitor for
// extended bursts during chunk-load light propagation; voxy worker reads
// block on the monitor and ingest visibly stalls. Removing the wrapper makes
// the reads non-blocking.
//
// SAFETY TRADEOFF (deliberately accepted):
// Without the wrapper, fastutil's Long2ObjectOpenHashMap is unsafe under
// concurrent read/write. Possible failure modes during a c2me write that
// races a voxy read:
//   (a) stale DataLayer reference — voxy bakes slightly-out-of-date light
//       values into the LOD section. Visible as: occasional LOD patch that's
//       a shade off until that chunk is re-ingested.
//   (b) null read mid-rehash — voxy's null-check (e.g. in
//       VoxelIngestService at the getDataLayerData call sites) treats as
//       "no lighting", section ingests dark. Visible as: occasional fully-
//       dark LOD section, self-healing on re-ingest.
//   (c) NPE from internal map state mid-rehash — caught by the worker's
//       outer error handler, section voxelisation fails for this attempt
//       and gets retried by distant-gen on its next pass.
// None of these affect block IDs, geometry, vanilla save data, or game
// stability — the corruption is bounded to lighting values in voxy's LOD
// storage and self-heals when the affected section is re-ingested.
//
// HISTORY: an earlier comment claimed "vanilla 1.20.1 only WRITES from the
// main thread" — this is true for stock vanilla but invalidated by c2me-
// threading-lighting which is in active use here. A revert attempt (commit
// c23b4fae, later reverted by 34db0fc3) restored vanilla's wrapper and
// caused visible LOD-streaming stalls. The proper long-term fix is to pre-
// snapshot DataLayer at chunk-load time on the calling/server thread (where
// the monitor is naturally held by surrounding lighting work), hand the
// snapshot to voxy workers. VoxelIngestService.enqueueIngest already does
// this via copyToPooledLightLayer; VoxyDistantGenSaveService.voxeliseChunk
// is the remaining caller that reads directly from a worker thread. Until
// that snapshot fix lands, the strip-the-wrapper hazard is accepted.
//
// VERIFY ON MC VERSION BUMP: if a future MC version moves light-state
// mutation into vanilla-internal worker threads in a way that interacts
// with voxy's reads (e.g. concurrent writers within MC itself), revisit.
@Mixin(LayerLightSectionStorage.class)
public class MixinLayerLightSectionStorage {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMaps;synchronize(Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;)Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;"), remap = false)
    private Long2ObjectMap<DataLayer> voxy$removeSynchronized(Long2ObjectMap<DataLayer> map) {
        return map;
    }
}
