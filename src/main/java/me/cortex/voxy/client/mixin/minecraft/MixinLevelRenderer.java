package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements IGetVoxyRenderSystem {
    @Shadow private @Nullable ClientLevel level;
    @Unique private VoxyRenderSystem renderer;

    // Snapshot of the inputs that actually determine voxy's render system, taken
    // when the current renderer was last built. LevelRenderer.allChanged() fires
    // for many things voxy is indifferent to - closing the video/shader options
    // screen with no changes, assorted other allChanged() callers, etc - and a
    // full recreate is expensive (System.gc + glFinish stalls + realloc of the
    // model/geometry/node subsystems + a full LOD re-mesh; that re-mesh is the
    // visible "reload"). This snapshot lets reloadVoxyRenderer skip the rebuild
    // when nothing voxy depends on actually changed.
    @Unique private ClientLevel voxy$boundLevel;
    @Unique private Object voxy$boundIrisPipeline;
    @Unique private int voxy$boundRenderDistance = -1;

    @Override
    public VoxyRenderSystem getVoxyRenderSystem() {
        return this.renderer;
    }

    @Inject(method = "allChanged()V", at = @At("RETURN"))//We want to inject before sodium
    private void reloadVoxyRenderer(CallbackInfo ci) {
        // Idempotency gate: only do the expensive recreate when an input voxy
        // genuinely depends on changed - the level, the iris pipeline identity
        // (shader toggle or shaderpack reload), or the vanilla render distance.
        // Otherwise this allChanged() is a no-op for voxy, so keep the existing
        // render system and its already-uploaded geometry instead of rebuilding.
        if (this.renderer != null
                && this.level == this.voxy$boundLevel
                && this.voxy$currentVoxyIrisPipeline() == this.voxy$boundIrisPipeline
                && this.voxy$currentRenderDistance() == this.voxy$boundRenderDistance) {
            return;
        }
        this.shutdownRenderer();
        if (this.level != null) {
            this.createRenderer();
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void voxy$captureSetWorld(ClientLevel world, CallbackInfo ci) {
        if (this.level != world) {
            this.shutdownRenderer();
        }
    }

    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void injectClose(CallbackInfo ci) {
        this.shutdownRenderer();
    }

    @Override
    public void shutdownRenderer() {
        if (this.renderer != null) {
            this.renderer.shutdown();
            this.renderer = null;
        }
        // Clear the snapshot so a subsequent createRenderer is never skipped by
        // the gate (renderer == null already forces a rebuild, but keep these
        // consistent).
        this.voxy$boundLevel = null;
        this.voxy$boundIrisPipeline = null;
        this.voxy$boundRenderDistance = -1;
    }

    @Override
    public void createRenderer() {
        if (this.renderer != null) throw new IllegalStateException("Cannot have multiple renderers");
        if (!VoxyConfig.CONFIG.enabled) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            Logger.info("Not creating renderer due to disabled rendering");
            return;
        }
        if (this.level == null) {
            Logger.error("Not creating renderer due to null world");
            return;
        }
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            // Single-frame condition: voxy's render mixin fires before
            // VoxyInstance has finished initialising (e.g. after a mid-session
            // toggle, or during the iris-pipeline-ready window). Defensive
            // null-skip works correctly; logged at DEBUG to avoid alarming
            // users on what is a handled no-op.
            Logger.debug("Not creating renderer due to null instance");
            return;
        }
        WorldEngine world = WorldIdentifier.ofEngine(this.level);
        if (world == null) {
            Logger.error("Null world selected");
            return;
        }
        try {
            this.renderer = new VoxyRenderSystem(world, instance.getServiceManager());
        } catch (RuntimeException e) {
            if (IrisUtil.irisShaderPackEnabled()) {
                IrisUtil.disableIrisShaders();
            } else {
                throw e;
            }
        }
        // Record the snapshot only when a renderer actually came up, so a
        // deferred create (any early-return above, or a caught iris failure that
        // left renderer == null) is retried on the next allChanged() rather than
        // being skipped by the gate.
        if (this.renderer != null) {
            this.voxy$boundLevel = this.level;
            this.voxy$boundIrisPipeline = this.voxy$currentVoxyIrisPipeline();
            this.voxy$boundRenderDistance = this.voxy$currentRenderDistance();
        }
        instance.updateDedicatedThreads();
    }

    // The iris pipeline object voxy would build against right now, mirroring
    // RenderPipelineFactory's decision: the current iris pipeline if it exposes
    // voxy pipeline data, else null (== voxy would use NormalRenderPipeline).
    // Object identity is the signal - an iris shaderpack reload produces a new
    // pipeline object, and toggling shaders on/off flips this to/from null - so
    // the gate sees real shader changes but ignores no-op allChanged() calls.
    @Unique
    private Object voxy$currentVoxyIrisPipeline() {
        if (!IrisUtil.IRIS_INSTALLED) return null;
        return this.voxy$currentVoxyIrisPipeline0();
    }

    // Split out so the iris class references are only reached when iris is
    // actually installed (matches RenderPipelineFactory), keeping no-iris packs
    // from trying to load iris classes.
    @Unique
    private Object voxy$currentVoxyIrisPipeline0() {
        var pipe = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        if (pipe instanceof IGetIrisVoxyPipelineData data && data.voxy$getPipelineData() != null) {
            return pipe;
        }
        return null;
    }

    @Unique
    private int voxy$currentRenderDistance() {
        var mc = Minecraft.getInstance();
        return (mc == null || mc.options == null) ? -1 : mc.options.renderDistance().get();
    }
}
