package me.cortex.voxy.client.core.model.berbake;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;

/**
 * Read-only diagnostic that classifies every registered block by how voxy's LOD bakery
 * would (or wouldn't) represent it, and — for empty-static-model block entities — whether
 * their real BlockEntityRenderer can be driven headless under {@link BakeLevel} (the
 * chest/water-wheel BER-bake scheme). Mod-agnostic: discovers candidates from the block
 * registry, so Create + every addon are covered with no curation.
 *
 * Writes a CSV to the game directory. No GL: static classification uses BakedModel.getQuads
 * and the BER probe captures into a counting VertexConsumer (rasterisation is skipped), so
 * this is safe to run from a client command on the client thread.
 *
 * Buckets:
 *  BAKED_FITS     - static model has quads reaching the cell faces; voxy bakes it normally.
 *  OVERHANG       - static quads extend beyond [0,1]; baked but clipped at the cell edge.
 *  FLOATING       - static quads sit strictly inside the cell (cube-fallback case today).
 *  EMPTY_WITH_BER - no static quads but has a BlockEntityRenderer (chest/water-wheel style).
 *  EMPTY_NO_BER   - no static quads, no renderer (truly invisible at LOD).
 *  INVISIBLE      - RenderShape.INVISIBLE (voxy skips by design).
 *  ERROR          - getQuads threw (broken third-party model).
 */
public final class BerBakeProbe {
    private BerBakeProbe() {}

    public static String run() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return "Voxy BER probe: no client level (join a world first).";
        }
        BakeLevel bakeLevel = new BakeLevel(mc.level);

        StringBuilder csv = new StringBuilder();
        csv.append("id,renderShape,staticBucket,anyStaticQuads,minX,minY,minZ,maxX,maxY,maxZ,genuineOverflow,")
           .append("isEntityBlock,hasRenderer,rendererClass,berAttempted,berThrew,berError,berQuadCount,berRenderTypes,")
           .append("berMinX,berMinY,berMinZ,berMaxX,berMaxY,berMaxZ\n");

        int[] counts = new int[Bucket.values().length];
        int berOk = 0, berThrew = 0, genuineOverflowCount = 0;

        for (Block block : ForgeRegistries.BLOCKS) {
            BlockState state = block.defaultBlockState();
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
            Row row = new Row();
            row.id = id == null ? block.getClass().getName() : id.toString();

            classifyStatic(mc, state, row);
            counts[row.bucket.ordinal()]++;

            // Run the headless BER probe for EVERY block that has a renderer, regardless
            // of static bucket. A block can have BOTH a static model AND a BER overhang
            // (Create water wheel: static hub core fills the cell -> BAKED_FITS, but the
            // spoked wheel is drawn by the BER and overhangs). Probing only empty-model
            // blocks would miss exactly those multi-cell candidates.
            if (row.hasRenderer) {
                probeBer(mc, bakeLevel, state, row);
                if (row.berAttempted) {
                    if (row.berThrew) berThrew++;
                    else if (row.berQuadCount > 0) berOk++;
                }
            }
            if (row.genuineOverflow()) genuineOverflowCount++;
            row.write(csv);
        }

        File out = new File(mc.gameDirectory, "voxy-berprobe.csv");
        try {
            try (BufferedWriter w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
                w.write(csv.toString());
            }
        } catch (Exception e) {
            return "Voxy BER probe: failed to write CSV: " + e;
        }

        StringBuilder sum = new StringBuilder("Voxy BER probe -> ").append(out.getName()).append("  |  ");
        for (Bucket b : Bucket.values()) {
            if (counts[b.ordinal()] > 0) sum.append(b).append('=').append(counts[b.ordinal()]).append(' ');
        }
        sum.append(" |  BER (any block w/ renderer): produced-quads=").append(berOk)
           .append(", threw=").append(berThrew)
           .append(" |  genuine multi-cell overflow blocks=").append(genuineOverflowCount);
        return sum.toString();
    }

    private enum Bucket { BAKED_FITS, OVERHANG, FLOATING, EMPTY_WITH_BER, EMPTY_NO_BER, INVISIBLE, ERROR }

    private static final class Row {
        String id;
        RenderShape renderShape;
        Bucket bucket;
        boolean anyStaticQuads;
        float minX, minY, minZ, maxX, maxY, maxZ;
        boolean isEntityBlock, hasRenderer;
        String rendererClass = "";
        boolean berAttempted, berThrew;
        String berError = "";
        int berQuadCount = -1;
        String berRenderTypes = "";
        float berMinX, berMinY, berMinZ, berMaxX, berMaxY, berMaxZ;

        // Genuine multi-cell overflow: geometry (static or BER) exceeds the cell by more
        // than a sub-texel epsilon, so boundary-touching cubes / float noise are excluded.
        boolean genuineOverflow() {
            final float E = 1f / 64f;
            return minX < -E || minY < -E || minZ < -E || maxX > 1f + E || maxY > 1f + E || maxZ > 1f + E;
        }

        void write(StringBuilder csv) {
            csv.append(q(id)).append(',').append(renderShape).append(',').append(bucket).append(',')
               .append(anyStaticQuads).append(',')
               .append(f(minX)).append(',').append(f(minY)).append(',').append(f(minZ)).append(',')
               .append(f(maxX)).append(',').append(f(maxY)).append(',').append(f(maxZ)).append(',')
               .append(genuineOverflow()).append(',')
               .append(isEntityBlock).append(',').append(hasRenderer).append(',').append(q(rendererClass)).append(',')
               .append(berAttempted).append(',').append(berThrew).append(',').append(q(berError)).append(',')
               .append(berQuadCount).append(',').append(q(berRenderTypes)).append(',')
               .append(f(berMinX)).append(',').append(f(berMinY)).append(',').append(f(berMinZ)).append(',')
               .append(f(berMaxX)).append(',').append(f(berMaxY)).append(',').append(f(berMaxZ)).append('\n');
        }
        private static String f(float v) { return Float.isFinite(v) ? String.format("%.3f", v) : ""; }
        private static String q(String s) {
            if (s == null) return "";
            if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
            return '"' + s.replace("\"", "\"\"") + '"';
        }
    }

    // Replicates the bakery's static-model quad collection (union of registration-table and
    // model-JSON render layers, all directions) without emitting — just to bucket the block.
    private static void classifyStatic(Minecraft mc, BlockState state, Row row) {
        row.renderShape = state.getRenderShape();
        row.isEntityBlock = state.getBlock() instanceof EntityBlock;
        if (row.renderShape == RenderShape.INVISIBLE) {
            row.bucket = Bucket.INVISIBLE;
            fillBerPresence(mc, state, row);
            return;
        }
        BakedModel model = mc.getModelManager().getBlockModelShaper().getBlockModel(state);
        var random = new SingleThreadedRandomSource(42L);
        var fromTable = ItemBlockRenderTypes.getRenderLayers(state);
        ChunkRenderTypeSet fromModel;
        try { fromModel = model.getRenderTypes(state, random, ModelData.EMPTY); }
        catch (Throwable t) { fromModel = ChunkRenderTypeSet.none(); }

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        boolean any = false;
        try {
            for (RenderType layer : ChunkRenderTypeSet.union(fromTable, fromModel)) {
                for (Direction dir : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
                    for (var quad : model.getQuads(state, dir, random, ModelData.EMPTY, layer)) {
                        any = true;
                        int[] vs = quad.getVertices();
                        for (int i = 0; i < 4; i++) {
                            int o = i * 8;
                            float vx = Float.intBitsToFloat(vs[o]), vy = Float.intBitsToFloat(vs[o + 1]), vz = Float.intBitsToFloat(vs[o + 2]);
                            if (vx < minX) minX = vx; if (vx > maxX) maxX = vx;
                            if (vy < minY) minY = vy; if (vy > maxY) maxY = vy;
                            if (vz < minZ) minZ = vz; if (vz > maxZ) maxZ = vz;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            row.bucket = Bucket.ERROR;
            row.berError = t.getClass().getSimpleName();
            fillBerPresence(mc, state, row);
            return;
        }

        row.anyStaticQuads = any;
        if (any) {
            row.minX = minX; row.minY = minY; row.minZ = minZ; row.maxX = maxX; row.maxY = maxY; row.maxZ = maxZ;
            final float RIM = 1f / 16f;
            boolean overhang = minX < -1e-4 || minY < -1e-4 || minZ < -1e-4
                    || maxX > 1f + 1e-4 || maxY > 1f + 1e-4 || maxZ > 1f + 1e-4;
            boolean floating = minX > RIM && maxX < 1 - RIM && minY > RIM && maxY < 1 - RIM && minZ > RIM && maxZ < 1 - RIM;
            row.bucket = overhang ? Bucket.OVERHANG : (floating ? Bucket.FLOATING : Bucket.BAKED_FITS);
        } else {
            row.bucket = Bucket.EMPTY_WITH_BER; // refined below if no renderer
        }
        fillBerPresence(mc, state, row);
        if (!any && !row.hasRenderer) {
            row.bucket = Bucket.EMPTY_NO_BER;
        }
    }

    private static void fillBerPresence(Minecraft mc, BlockState state, Row row) {
        if (!(state.getBlock() instanceof EntityBlock eb)) return;
        try {
            BlockEntity be = eb.newBlockEntity(BlockPos.ZERO, state);
            if (be == null) return;
            BlockEntityRenderer<?> r = mc.getBlockEntityRenderDispatcher().getRenderer(be);
            if (r != null) {
                row.hasRenderer = true;
                row.rendererClass = r.getClass().getName();
            }
        } catch (Throwable ignored) {}
    }

    // Actually drive the BER headless under BakeLevel and record whether it produced geometry.
    @SuppressWarnings("unchecked")
    private static void probeBer(Minecraft mc, BakeLevel bakeLevel, BlockState state, Row row) {
        if (!(state.getBlock() instanceof EntityBlock eb)) return;
        row.berAttempted = true;
        try {
            bakeLevel.setTarget(state).clearFaceNeighbours();
            BlockEntity be = eb.newBlockEntity(BlockPos.ZERO, state);
            if (be == null) { row.berAttempted = false; return; }
            be.setLevel(bakeLevel);
            BlockEntityRenderer<BlockEntity> r =
                    (BlockEntityRenderer<BlockEntity>) mc.getBlockEntityRenderDispatcher().getRenderer(be);
            if (r == null) { row.berAttempted = false; return; }

            CountingBufferSource buffers = new CountingBufferSource();
            r.render(be, 0f, new PoseStack(), buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            row.berQuadCount = buffers.consumer.vertexCount / 4;
            row.berRenderTypes = String.join("|", buffers.renderTypes);
            // Capture the BER geometry's true bbox (Flywheel kinetics like water wheels have
            // an empty static model, so this is the only extent signal). For blocks whose
            // static model was empty, surface the BER bbox in the row's main bbox columns so
            // the curation list shows real extent regardless of geometry source.
            var c = buffers.consumer;
            if (c.vertexCount > 0) {
                row.berMinX = c.minX; row.berMinY = c.minY; row.berMinZ = c.minZ;
                row.berMaxX = c.maxX; row.berMaxY = c.maxY; row.berMaxZ = c.maxZ;
                if (!row.anyStaticQuads) {
                    row.minX = c.minX; row.minY = c.minY; row.minZ = c.minZ;
                    row.maxX = c.maxX; row.maxY = c.maxY; row.maxZ = c.maxZ;
                }
            }
        } catch (Throwable t) {
            row.berThrew = true;
            row.berError = t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
        }
    }

    private static final class CountingBufferSource implements MultiBufferSource {
        final CountingConsumer consumer = new CountingConsumer();
        final Set<String> renderTypes = new LinkedHashSet<>();
        @Override public VertexConsumer getBuffer(RenderType type) {
            renderTypes.add(String.valueOf(type));
            return consumer;
        }
    }

    // Counts vertices AND tracks the geometry bbox; no-ops everything else.
    private static final class CountingConsumer implements VertexConsumer {
        int vertexCount = 0;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        @Override public VertexConsumer vertex(double x, double y, double z) {
            vertexCount++;
            float fx = (float) x, fy = (float) y, fz = (float) z;
            if (fx < minX) minX = fx; if (fx > maxX) maxX = fx;
            if (fy < minY) minY = fy; if (fy > maxY) maxY = fy;
            if (fz < minZ) minZ = fz; if (fz > maxZ) maxZ = fz;
            return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() {}
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }
}
