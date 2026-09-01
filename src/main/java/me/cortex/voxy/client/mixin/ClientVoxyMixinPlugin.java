package me.cortex.voxy.client.mixin;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.ForgePlatform;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    private static boolean sodiumLegacy = true;
    private static boolean valkyrienSkiesInstalled;
    private static boolean nvidiumInstalled;
    private static boolean flashbackInstalled;
    private static boolean irisInstalled;

    @Override
    public void onLoad(String mixinPackage) {
        valkyrienSkiesInstalled = ForgePlatform.isModLoaded("valkyrienskies");
        nvidiumInstalled = ForgePlatform.isModLoaded("nvidium");
        flashbackInstalled = ForgePlatform.isModLoaded("flashback");
        irisInstalled = ForgePlatform.isModLoaded("iris") || ForgePlatform.isModLoaded("oculus");

        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("me/jellysquid/mods/sodium/client/render/SodiumWorldRenderer.class")) {
            if (stream == null) {
                Logger.error("SodiumWorldRenderer class not found");
                return;
            }

            ClassReader reader = new ClassReader(stream);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);

            for (MethodNode method : node.methods) {
                if (method.name.equals("drawChunkLayer") && method.desc.contains("ChunkRenderMatrices")) {
                    sodiumLegacy = false;
                    break;
                }
            }
        } catch (Exception exception) {
            Logger.error(exception);
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("flashback.MixinFlashbackMeta")
                || mixinClassName.endsWith("flashback.MixinFlashbackRecorder")) {
            return flashbackInstalled;
        }

        // Nvidium is Fabric-only on 1.19.2. Keep the source-compatible shim but
        // never attempt to apply it in a Forge runtime.
        if (mixinClassName.endsWith("nvidium.MixinRenderPipeline")) {
            return false;
        }

        if (mixinClassName.contains(".iris.")) {
            return irisInstalled;
        }

        return true;
    }

    @Override
    public List<String> getMixins() {
        if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            return List.of(sodiumLegacy
                    ? "sodium.MixinSodiumWorldRendererLegacy"
                    : "sodium.MixinSodiumWorldRenderer");
        }
        return List.of("sodium.MixinDefaultChunkRenderer");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
