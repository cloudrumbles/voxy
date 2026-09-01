package me.cortex.voxy.client.core.model.bakery;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.client.core.model.berbake.BakeLevel;
import me.cortex.voxy.client.core.model.berbake.IBerBakeHandler;
import me.cortex.voxy.client.core.model.berbake.VoxyBerBakeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.ARBDrawBuffersBlend;
import org.lwjgl.opengl.GL14;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL40.glBlendFuncSeparatei;
import static org.lwjgl.opengl.GL45.glTextureBarrier;

import com.mojang.blaze3d.vertex.PoseStack;

public class ModelTextureBakery {
    //Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    // Dedupe set for per-BlockState bake-failure warnings. Some third-party
    // BakedModels (first observed: dtbloomingnature LargePalmLeavesBakedModel)
    // throw from getQuads(); without this guard a single broken model takes
    // down the render thread and spams the log every frame.
    private static final java.util.Set<BlockState> WARNED_BAKE_FAILURES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Same per-BlockState dedupe for BER-bake failures (renderer threw / unmodelled
    // Level surface hit); the block then falls through to the particle-cube path.
    private static final java.util.Set<BlockState> WARNED_BER_FAILURES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Cached determinism-gate verdict per BlockState (true = neighbour-independent, safe
    // to BER-bake), plus a dedupe set for the one-time rejection log. Handlers that
    // declare isKnownStateDetermined() bypass the gate and never touch these.
    private static final java.util.Map<BlockState, Boolean> BER_DETERMINISM = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<BlockState> WARNED_BER_NONDETERMINISTIC = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Stricter gate: also probe each of the 6 face axes alone (catches single-axis
    // connectors that look symmetric when fully surrounded). Off by default — the
    // uniform all-self probe catches the common connection patterns at lower cost.
    private static final boolean STRICT_PER_DIRECTION_PROBE = false;

    private final GlViewCapture capture;
    private final ReuseVertexConsumer vc = new ReuseVertexConsumer();

    // Slice offset for the current renderToStream call. (0,0,0) for an ordinary bake;
    // non-zero only when baking a multi-cell slice (a synthetic id), set via the
    // offset overload of renderToStream and consumed by the BER branch. renderToStream
    // runs single-threaded on the render thread, so a plain field is safe.
    private int sliceDx, sliceDy, sliceDz;
    // After a BER bake of an offset-0 capture, holds the geometry bbox (block-local) so
    // the caller can record a multi-cell block's footprint. NaN/unset otherwise.
    public float lastBerMinX, lastBerMinY, lastBerMinZ, lastBerMaxX, lastBerMaxY, lastBerMaxZ;
    public boolean lastBerHadBox;

    // Synthetic level reused across BER bakes; rebuilt if the live client level changes
    // (dimension switch). Render-thread only, like the rest of renderToStream.
    private BakeLevel bakeLevel;
    private ClientLevel bakeLevelSource;

    private final int width;
    private final int height;
    public ModelTextureBakery(int width, int height) {
        this.capture = new GlViewCapture(width, height);
        this.width = width;
        this.height = height;
    }

    public static int getMetaFromLayer(RenderType layer) {
        // hasDiscard must include cutoutMipped — that's the whole point of
        // cutout-family render types, just with vs without mipmaps. Vanilla
        // grass_block is registered as cutoutMipped, and omitting it here
        // caused the bake shader to keep transparent pixels in the side
        // texture's grass-strand gaps; at LOD distance those pixels showed
        // through to the void, producing the "black between strands"
        // artifact while solid neighbours like snow_block looked fine.
        boolean hasDiscard = layer == RenderType.cutout() ||
                layer == RenderType.cutoutMipped() ||
                layer == RenderType.translucent()||
                layer == RenderType.tripwire();

        // bit 1 was originally a layer-type-derived "use normal mipping" flag,
        // but bakery/position_tex.fsh hard-overrides mipbias to -16 because the
        // alpha-cutoff path is broken for >16x16 resource packs. Until that
        // shader bug is fixed (TODO in position_tex.fsh), bit 1 must stay set
        // and the per-layer isMipped derivation is moot.
        return (hasDiscard?1:0) | 2;
    }

    private void bakeBlockModel(BlockState state, RenderType primaryLayer) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;//Dont bake if invisible
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        // Forge 1.20.1: a single block can render across multiple layers
        // (e.g. grass = solid dirt + cutout_mipped overlay). The deprecated
        // 3-arg getQuads() returns only one layer's quads on Forge, which is
        // why grass sides came out with the grass fringe alone and the dirt
        // underlay missing (transparent/black). Iterate the full
        // ChunkRenderTypeSet and ask the model for each layer's quads via the
        // 5-arg Forge overload.
        //
        // Two independent API surfaces declare render layers on Forge:
        //   1. ItemBlockRenderTypes.getRenderLayers(state) — the vanilla
        //      registration table, populated via setRenderLayer() calls in
        //      mod client setup.
        //   2. model.getRenderTypes(state, random, modelData) — the
        //      per-model render type declared in the model JSON's
        //      "render_type" field (Forge feature; lives on the BakedModel
        //      itself, not in the global table).
        // RU and similar mods declare cutout in model JSON without calling
        // setRenderLayer; querying only (1) gets back [solid], and asking
        // the cutout-only model for solid-layer quads returns nothing, so
        // the bake is empty and the block renders invisible at LOD. Union
        // both sources to catch either-or-both wiring.
        var random = new SingleThreadedRandomSource(42L);
        var fromTable = ItemBlockRenderTypes.getRenderLayers(state);
        ChunkRenderTypeSet fromModel;
        try {
            fromModel = model.getRenderTypes(state, random, ModelData.EMPTY);
        } catch (Throwable t) {
            // Some BakedModel implementations override getRenderTypes and
            // throw; treat as "no contribution" rather than killing the bake.
            fromModel = ChunkRenderTypeSet.none();
        }
        // While emitting, track the model's geometry bounding box (block-local
        // [0,1]) so we can detect "floating island" blocks: models whose entire
        // geometry sits strictly inside the cell, reaching none of the six face
        // rims. These bake as a square suspended in mid-cell connected to
        // nothing — the failure mode of unconnected Create-style pipe/machine
        // cores whose connecting geometry only appears with neighbour context
        // (which voxy can't supply at bake time). Anything that reaches a face
        // rim (fences/bars touch top+bottom, torches/redstone touch the floor,
        // plants root at y=0, buttons touch a wall) is left exactly as-is.
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        boolean anyQuads = false;

        var layers = ChunkRenderTypeSet.union(fromTable, fromModel);
        for (RenderType layer : layers) {
            int meta = getMetaFromLayer(layer);
            for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
                try {
                    var quads = model.getQuads(state, direction, random, ModelData.EMPTY, layer);
                    for (var quad : quads) {
                        this.vc.quad(quad, meta|(quad.isTinted()?4:0));
                        anyQuads = true;
                        int[] vs = quad.getVertices();
                        for (int i = 0; i < 4; i++) {
                            int off = i * 8;
                            float vx = Float.intBitsToFloat(vs[off]);
                            float vy = Float.intBitsToFloat(vs[off + 1]);
                            float vz = Float.intBitsToFloat(vs[off + 2]);
                            if (vx < minX) minX = vx; if (vx > maxX) maxX = vx;
                            if (vy < minY) minY = vy; if (vy > maxY) maxY = vy;
                            if (vz < minZ) minZ = vz; if (vz > maxZ) maxZ = vz;
                        }
                    }
                } catch (Throwable t) {
                    // Third-party BakedModels can throw arbitrary exceptions
                    // (NPEs, AIOOBs, even Errors from native code) from
                    // getQuads(). Skip the broken direction rather than
                    // killing the render thread; warn once per BlockState.
                    if (WARNED_BAKE_FAILURES.add(state)) {
                        Logger.warn("Skipping LOD bake of " + state
                                + " (layer=" + layer + ", direction=" + direction
                                + ", model=" + model.getClass().getName() + "): "
                                + t.getClass().getName() + ": " + t.getMessage());
                    }
                }
            }
        }

        // Two cases get replaced by a full cube of the block's particle texture
        // (DH-style), so they read as solid at LOD instead of broken/invisible:
        //
        //  1. Floating island: geometry sits strictly inside the inner 14^3
        //     region, reaching none of the six face rims (outermost 1/16 shell)
        //     — the unconnected Create-style pipe/machine-core failure mode.
        //  2. Empty static model: the block baked no quads at all. These render
        //     fine in-world via a BlockEntityRenderer but carry an empty /
        //     particle-only block model (Create belts -> belt/particle.json with
        //     "elements":[], chests, signs, banners, ...), so voxy bakes nothing
        //     and they vanish at LOD. RenderShape.INVISIBLE was already returned
        //     above, so reaching here with no quads means a visible-intended
        //     block that simply has no static geometry — safe to represent as a
        //     cube (it was invisible before, so this cannot regress it).
        final float RIM = 1.0f / 16.0f;
        boolean floating = anyQuads
                && minX > RIM && maxX < 1.0f - RIM
                && minY > RIM && maxY < 1.0f - RIM
                && minZ > RIM && maxZ < 1.0f - RIM;
        if (floating || !anyQuads) {
            // Only replace with a cube if the block has a real particle texture.
            // If it doesn't (particle resolves to the missing-texture sprite),
            // leave it untouched: floating geometry keeps its quads, an empty
            // model stays empty — never substitute a magenta/blank cube.
            TextureAtlasSprite sprite = particleCubeSprite(state);
            if (sprite != null) {
                float ox = this.vc.offX, oy = this.vc.offY, oz = this.vc.offZ;
                this.vc.reset(); // floating: drop the interior quads; empty: no-op
                this.vc.offX = ox; this.vc.offY = oy; this.vc.offZ = oz;//preserve slice offset across reset
                this.bakeFullCube(primaryLayer, sprite);
            }
        }
    }


    // Lazily (re)build the synthetic bake level from the live client level. Returns
    // false when there's no live level to source registry/dimension args from.
    private boolean ensureBakeLevel() {
        ClientLevel live = Minecraft.getInstance().level;
        if (live == null) {
            return false;
        }
        if (this.bakeLevel == null || this.bakeLevelSource != live) {
            this.bakeLevel = new BakeLevel(live);
            this.bakeLevelSource = live;
        }
        return true;
    }

    // Capture a block entity's real BlockEntityRenderer output into this.vc, in the
    // same [0,1] block-local space and 24B vertex layout the block-model path produces.
    // The BER applies its own facing/orientation and ModelPart geometry is pre-divided
    // by 16, so with an identity PoseStack the quads land in the unit cell directly.
    // Colour/normal/lightmap are dropped by ReuseVertexConsumer (flat albedo), which is
    // exactly what voxy wants — it relights LODs itself. Single atlas only (the handler
    // declares which); the GL texture is bound by the caller from handler.atlas().
    // Assumes this.bakeLevel is already configured (target + neighbours) and reset.
    private void captureBer(BlockState state, IBerBakeHandler handler, int dx, int dy, int dz) {
        var be = handler.createBlockEntity(BlockPos.ZERO, state, this.bakeLevel);
        if (be == null) {
            return;//Not an EntityBlock / no BE; leave vc empty -> caller falls through
        }
        // BER geometry is alpha-cutout (chest lid/lock against transparent atlas gaps).
        this.vc.setDefaultMeta(getMetaFromLayer(RenderType.cutout()));
        MultiBufferSource buffers = renderType -> this.vc;//route every layer to the bakery vc
        // For a multi-cell slice, offset by (-dx,-dy,-dz) so the geometry occupying cell
        // (dx,dy,dz) slides into the [0,1] capture cell; the rest falls outside the
        // orthographic viewport and is GPU-clipped. We use the vertex-consumer offset
        // (uniform with the static-model path) and an identity PoseStack, rather than
        // translating the PoseStack — the vc tracks the true (pre-offset) bbox.
        this.vc.offX = -dx; this.vc.offY = -dy; this.vc.offZ = -dz;
        handler.render(be, new PoseStack(), buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        this.vc.setDefaultMeta(0);
    }

    // The normal bake: the block entity in its own cell (offset 0), air neighbours.
    // Caller resets vc.
    private void bakeBlockEntityModel(BlockState state, IBerBakeHandler handler) {
        bakeBlockEntityModel(state, handler, 0, 0, 0);
    }

    // Slice bake: capture only the part of the BER geometry that lives in cell
    // (dx,dy,dz) relative to the block's own cell.
    private void bakeBlockEntityModel(BlockState state, IBerBakeHandler handler, int dx, int dy, int dz) {
        if (!ensureBakeLevel()) {
            return;
        }
        this.bakeLevel.setTarget(state).clearFaceNeighbours().setNeighbourFill(Blocks.AIR.defaultBlockState());
        captureBer(state, handler, dx, dy, dz);
    }

    // Determinism gate: is this handler's BER output a pure function of the BlockState
    // (i.e. independent of neighbours)? Only such states are safe to bake-and-cache per
    // state. Cheap, conservative probe — NOT an exhaustive proof: bake the target in
    // isolation, then re-bake with all neighbours = the block itself (the configuration
    // that triggers real-world connection logic — pipes/cogs connect to like blocks) and
    // compare the captured geometry. A difference ⇒ neighbour-dependent ⇒ reject (fall
    // back to the particle cube). First-difference early exit; verdict cached per state.
    // STRICT_PER_DIRECTION_PROBE additionally tests each face axis alone (off by default).
    private boolean berBakeIsDeterministic(BlockState state, IBerBakeHandler handler) {
        Boolean cached = BER_DETERMINISM.get(state);
        if (cached != null) {
            return cached;
        }
        boolean verdict = computeBerDeterminism(state, handler);
        BER_DETERMINISM.put(state, verdict);
        if (!verdict && WARNED_BER_NONDETERMINISTIC.add(state)) {
            Logger.info("BER LOD bake rejected (neighbour-dependent geometry), using particle cube: " + state);
        }
        return verdict;
    }

    private boolean computeBerDeterminism(BlockState state, IBerBakeHandler handler) {
        if (!ensureBakeLevel()) {
            return false;
        }
        try {
            // Baseline: isolated (air neighbours).
            this.vc.reset();
            this.bakeLevel.setTarget(state).clearFaceNeighbours().setNeighbourFill(Blocks.AIR.defaultBlockState());
            captureBer(state, handler, 0, 0, 0);
            if (this.vc.isEmpty()) {
                return false;//nothing captured -> not BER-bakeable, fall through to cube
            }
            long baseHash = this.vc.contentHash();

            // Probe: every neighbour = the block itself (maximal connection signal).
            this.vc.reset();
            this.bakeLevel.setTarget(state).clearFaceNeighbours().setNeighbourFill(state);
            captureBer(state, handler, 0, 0, 0);
            if (this.vc.contentHash() != baseHash) {
                return false;//neighbour-dependent (early exit)
            }

            if (STRICT_PER_DIRECTION_PROBE) {
                for (Direction d : Direction.values()) {
                    this.vc.reset();
                    this.bakeLevel.setTarget(state).setNeighbourFill(Blocks.AIR.defaultBlockState())
                            .clearFaceNeighbours().setFaceNeighbour(d, state);
                    captureBer(state, handler, 0, 0, 0);
                    if (this.vc.contentHash() != baseHash) {
                        return false;//connects on a single axis (early exit)
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            // BER not safely runnable in isolation (hit BakeLevel's throw-gate, etc.):
            // treat as non-bakeable -> fall through to cube.
            return false;
        }
    }

    // The shared 6-orthographic-view emission used by the block and BER bake paths.
    // Assumes BudgetBufferRenderer.setup() has already bound the vertex data + texture.
    private void renderSixViews() {
        var mat = new Matrix4f();
        for (int i = 0; i < VIEWS.length; i++) {
            if (i == 1 || i == 2 || i == 4) {
                glCullFace(GL_FRONT);
            } else {
                glCullFace(GL_BACK);
            }

            glViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

            //The projection matrix
            mat.set(2, 0, 0, 0,
                    0, 2, 0, 0,
                    0, 0, -1f, 0,
                    -1, -1, 0, 1)
                    .mul(VIEWS[i]);

            BudgetBufferRenderer.render(mat);
        }
    }

    // Reference unit-cube quads, captured once from Stone's baked model: 6
    // full-face quads already wound correctly for this capture pipeline. The
    // cube-proxy path reuses their geometry (positions + winding) and only
    // remaps UVs to the proxied block's particle sprite, so we inherit
    // known-good winding instead of hand-emitting faces.
    private BakedQuad[] referenceCubeQuads;

    private BakedQuad[] getReferenceCubeQuads() {
        if (this.referenceCubeQuads == null) {
            var random = new SingleThreadedRandomSource(42L);
            var stoneState = Blocks.STONE.defaultBlockState();
            var stone = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(stoneState);
            var list = new java.util.ArrayList<BakedQuad>(6);
            for (Direction dir : Direction.values()) {
                list.addAll(stone.getQuads(stoneState, dir, random, ModelData.EMPTY, RenderType.solid()));
            }
            this.referenceCubeQuads = list.toArray(new BakedQuad[0]);
        }
        return this.referenceCubeQuads;
    }

    // The block's particle (breaking) texture if it is a real, representative
    // texture; null if it resolves to the missing-texture sprite (or none).
    // Used to decide whether a floating/empty block can be shown as a cube.
    private static TextureAtlasSprite particleCubeSprite(BlockState state) {
        var model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
        TextureAtlasSprite sprite = model.getParticleIcon(ModelData.EMPTY);
        if (sprite == null || sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
            return null;
        }
        return sprite;
    }

    // Render a block as a full cube of the given (particle) texture (DH-style),
    // so floating-island and empty-static-model blocks read as solid at LOD.
    private void bakeFullCube(RenderType layer, TextureAtlasSprite sprite) {
        int meta = getMetaFromLayer(layer);
        for (BakedQuad ref : getReferenceCubeQuads()) {
            this.vc.quad(remapQuadUv(ref, sprite), meta);
        }
    }

    // Clone a reference quad, rescaling each vertex's atlas UV from the source
    // sprite's atlas region into the target sprite's region. vc.quad consumes
    // only position (inherited, correct) and uv (remapped here).
    private static BakedQuad remapQuadUv(BakedQuad ref, TextureAtlasSprite target) {
        TextureAtlasSprite src = ref.getSprite();
        int[] in = ref.getVertices();
        int[] out = in.clone();
        float su0 = src.getU0(), su1 = src.getU1(), sv0 = src.getV0(), sv1 = src.getV1();
        float tu0 = target.getU0(), tu1 = target.getU1(), tv0 = target.getV0(), tv1 = target.getV1();
        for (int i = 0; i < 4; i++) {
            int off = i * 8;
            float u = Float.intBitsToFloat(in[off + 4]);
            float v = Float.intBitsToFloat(in[off + 5]);
            float lu = (su1 != su0) ? (u - su0) / (su1 - su0) : 0f;
            float lv = (sv1 != sv0) ? (v - sv0) / (sv1 - sv0) : 0f;
            out[off + 4] = Float.floatToRawIntBits(tu0 + lu * (tu1 - tu0));
            out[off + 5] = Float.floatToRawIntBits(tv0 + lv * (tv1 - tv0));
        }
        return new BakedQuad(out, ref.getTintIndex(), ref.getDirection(), target, ref.isShade());
    }

    private void bakeFluidState(BlockState state, RenderType layer, int face) {
        {
            //TODO: somehow set the tint flag per quad or something?
            int metadata = getMetaFromLayer(layer);
            //Just assume all fluids are tinted, if they arnt it should be implicitly culled in the model baking phase
            // since it wont have the colour provider
            metadata |= 4;//Has tint
            this.vc.setDefaultMeta(metadata);//Set the meta while baking
        }
        Minecraft.getInstance().getBlockRenderer().renderLiquid(BlockPos.ZERO, new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }

                //Fixme:
                // This makes it so that the top face of water is always air, if this is commented out
                //  the up block will be a liquid state which makes the sides full
                // if this is uncommented, that issue is fixed but e.g. stacking water layers ontop of eachother
                //  doesnt fill the side of the block

                //if (pos.getY() == 1) {
                //    return Blocks.AIR.getDefaultState();
                //}
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }

                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }
        }, this.vc, state, state.getFluidState());
        this.vc.setDefaultMeta(0);//Reset default meta
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getNormal();
        int dot = fv.getX()*pos.getX() + fv.getY()*pos.getY() + fv.getZ()*pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.capture.free();
        this.vc.free();
    }


    public int renderToStream(BlockState state, int streamBuffer, int streamOffset) {
        return renderToStream(state, streamBuffer, streamOffset, 0, 0, 0);
    }

    // Offset overload: bake the slice of a BER's geometry that occupies cell (dx,dy,dz)
    // relative to the block's own cell. (0,0,0) == the ordinary single-cell bake.
    public int renderToStream(BlockState state, int streamBuffer, int streamOffset, int dx, int dy, int dz) {
        this.sliceDx = dx; this.sliceDy = dy; this.sliceDz = dz;
        this.lastBerHadBox = false;
        this.capture.clear();
        boolean isBlock = true;
        RenderType layer;
        if (state.getBlock() instanceof LiquidBlock) {
            layer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
            isBlock = false;
        } else {
            if (state.getBlock() instanceof LeavesBlock) {
                layer = RenderType.solid();
            } else {
                layer = ItemBlockRenderTypes.getChunkRenderType(state);
            }
        }

        //Setup GL state
        int[] viewdat = new int[4];
        int blockTextureId;

        {
            glEnable(GL_STENCIL_TEST);
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_CULL_FACE);
            if (layer == RenderType.translucent()) {
                glEnablei(GL_BLEND, 0);
                glDisablei(GL_BLEND, 1);
                ARBDrawBuffersBlend.glBlendFuncSeparateiARB(0, GL_ONE_MINUS_DST_ALPHA, GL_DST_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            } else {
                glDisable(GL_BLEND);//FUCK YOU INTEL (screams), for _some reason_ discard or something... JUST DOESNT WORK??
                //glBlendFuncSeparate(GL_ONE, GL_ZERO, GL_ONE, GL_ONE);
            }

            glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
            glStencilFunc(GL_ALWAYS, 1, 0xFF);
            glStencilMask(0xFF);

            glGetIntegerv(GL_VIEWPORT, viewdat);//TODO: faster way todo this, or just use main framebuffer resolution

            //Bind the capture framebuffer
            glBindFramebuffer(GL_FRAMEBUFFER, this.capture.framebuffer.id);

            // In 1.20.1, AbstractTexture.getId() returns the GL texture ID directly
            blockTextureId = Minecraft.getInstance().getTextureManager().getTexture(new ResourceLocation("minecraft", "textures/atlas/blocks.png")).getId();
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        if (isBlock) {
            // BER bake path: if a handler is registered for this block entity, capture
            // its real BlockEntityRenderer output (chests etc.) instead of the
            // empty-model -> particle-cube fallback. On empty capture or any thrown
            // exception we fall through to the normal block model bake, so the worst
            // case is exactly the prior behaviour (the cube).
            IBerBakeHandler ber = VoxyBerBakeRegistry.INSTANCE.resolve(state);
            boolean usedBer = false;
            // Gate: known-determined handlers (chests) bake directly; unknown ones must
            // pass the empirical neighbour-independence check or fall through to the cube.
            if (ber != null && !ber.isKnownStateDetermined() && !berBakeIsDeterministic(state, ber)) {
                ber = null;
            }
            if (ber != null) {
                this.vc.reset();
                boolean captured = false;
                try {
                    this.bakeBlockEntityModel(state, ber, this.sliceDx, this.sliceDy, this.sliceDz);
                    captured = !this.vc.isEmpty();
                    // Record the geometry bbox of an offset-0 capture so the caller can
                    // derive this block's multi-cell footprint. Slice (offset!=0) bakes
                    // are translated, so their bbox is not the whole-block footprint.
                    if (captured && this.sliceDx == 0 && this.sliceDy == 0 && this.sliceDz == 0) {
                        this.lastBerMinX = this.vc.minX; this.lastBerMinY = this.vc.minY; this.lastBerMinZ = this.vc.minZ;
                        this.lastBerMaxX = this.vc.maxX; this.lastBerMaxY = this.vc.maxY; this.lastBerMaxZ = this.vc.maxZ;
                        this.lastBerHadBox = true;
                    }
                } catch (Throwable t) {
                    if (WARNED_BER_FAILURES.add(state)) {
                        Logger.warn("Skipping BER LOD bake of " + state + ": "
                                + t.getClass().getName() + ": " + t.getMessage());
                    }
                    this.vc.reset();
                }
                if (captured) {
                    int berTexId = Minecraft.getInstance().getTextureManager()
                            .getTexture(ber.atlas()).getId();
                    isAnyShaded |= this.vc.anyShaded;
                    isAnyDarkend |= this.vc.anyDarkendTex;
                    BudgetBufferRenderer.setup(this.vc.getAddress(), this.vc.quadCount(), berTexId);
                    this.renderSixViews();
                    glBindVertexArray(0);
                    usedBer = true;
                }
            }

            if (!usedBer) {
                this.vc.reset();
                this.vc.offX = -this.sliceDx; this.vc.offY = -this.sliceDy; this.vc.offZ = -this.sliceDz;
                this.bakeBlockModel(state, layer);
                isAnyShaded |= this.vc.anyShaded;
                isAnyDarkend |= this.vc.anyDarkendTex;
                // Record the static model's true (pre-offset) bbox for an offset-0 bake so
                // multi-cell footprint expansion works for blocks with overhanging static
                // geometry (e.g. Create cogs, whose teeth reach slightly past the cell).
                if (this.sliceDx == 0 && this.sliceDy == 0 && this.sliceDz == 0 && !this.vc.isEmpty()) {
                    this.lastBerMinX = this.vc.minX; this.lastBerMinY = this.vc.minY; this.lastBerMinZ = this.vc.minZ;
                    this.lastBerMaxX = this.vc.maxX; this.lastBerMaxY = this.vc.maxY; this.lastBerMaxZ = this.vc.maxZ;
                    this.lastBerHadBox = true;
                }
                if (!this.vc.isEmpty()) {//only render if there... is shit to render
                    //Setup for continual emission
                    BudgetBufferRenderer.setup(this.vc.getAddress(), this.vc.quadCount(), blockTextureId);//note: this.vc.buffer.address NOT this.vc.ptr
                    this.renderSixViews();
                }
                glBindVertexArray(0);
            }
        } else {//Is fluid, slow path :(

            if (!(state.getBlock() instanceof LiquidBlock)) throw new IllegalStateException();

            var mat = new Matrix4f();
            for (int i = 0; i < VIEWS.length; i++) {
                if (i==1||i==2||i==4) {
                    glCullFace(GL_FRONT);
                } else {
                    glCullFace(GL_BACK);
                }

                this.vc.reset();
                this.bakeFluidState(state, layer, i);
                if (this.vc.isEmpty()) continue;
                isAnyShaded |= this.vc.anyShaded;
                isAnyDarkend |= this.vc.anyDarkendTex;
                BudgetBufferRenderer.setup(this.vc.getAddress(), this.vc.quadCount(), blockTextureId);

                glViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

                //The projection matrix
                mat.set(2, 0, 0, 0,
                        0, 2, 0, 0,
                        0, 0, -1f, 0,
                        -1, -1, 0, 1)
                        .mul(VIEWS[i]);

                BudgetBufferRenderer.render(mat);
            }
            glBindVertexArray(0);
        }

        //"Restore" gl state
        glViewport(viewdat[0], viewdat[1], viewdat[2], viewdat[3]);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_BLEND);

        //Finish and download
        glTextureBarrier();
        this.capture.emitToStream(streamBuffer, streamOffset);

        glBindFramebuffer(GL_FRAMEBUFFER, this.capture.framebuffer.id);
        glClearDepth(1);
        glClear(GL_DEPTH_BUFFER_BIT);
        if (layer == RenderType.translucent()) {
            //reset the blend func
            GL14.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        }

        return (isAnyShaded?1:0)|(isAnyDarkend?2:0);
    }




    static {
        //the face/direction is the face (e.g. down is the down face)
        addView(0, -90,0, 0, 0);//Direction.DOWN
        addView(1, 90,0, 0, 0b100);//Direction.UP

        addView(2, 0,180, 0, 0b001);//Direction.NORTH
        addView(3, 0,0, 0, 0);//Direction.SOUTH

        addView(4, 0,90, 270, 0b100);//Direction.WEST
        addView(5, 0,270, 270, 0);//Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f,0.5f,0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,0,1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1,0,0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,1,0), yaw));
        stack.last().pose().mul(new Matrix4f().scale(1-2*(flip&1), 1-(flip&2), 1-((flip>>1)&2)));
        stack.translate(-0.5f,-0.5f,-0.5f);
        VIEWS[i] = new Matrix4f(stack.last().pose());
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1/Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
