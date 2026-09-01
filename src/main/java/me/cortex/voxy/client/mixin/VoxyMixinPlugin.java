package me.cortex.voxy.client.mixin;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class VoxyMixinPlugin implements IMixinConfigPlugin {
    // Gate iris mixins on mod presence, not on whether Iris's class has been
    // loaded. The previous Class.forName probe had two problems: (1) it defined
    // the target class in the classloader as a side effect, triggering
    // MixinTargetAlreadyLoadedException when Mixin later tried to transform it;
    // and (2) the answer was inverted relative to the semantics mixin needs —
    // we want to apply the mixin when iris/oculus is installed, not when the
    // target is already loaded (which is the one case where we can't).
    // LoadingModList is populated by the time Mixin processes configs, so a
    // ModList-based check works here and avoids touching the target class.
    // Accepts both mod IDs: "iris" (upstream Fabric) and "oculus" (Forge port).
    private static final boolean IRIS_AVAILABLE = resolveIrisAvailable();

    private static boolean resolveIrisAvailable() {
        try {
            LoadingModList list = LoadingModList.get();
            if (list == null) return false;
            return list.getModFileById("iris") != null
                    || list.getModFileById("oculus") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".iris.")) {
            return IRIS_AVAILABLE;
        }
        if (mixinClassName.contains(".nvidium.")) {
            return false; // Nvidium not available on Forge 1.20.1
        }
        if (mixinClassName.contains(".flashback.")) {
            return false; // Flashback not available on Forge 1.20.1
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() { return null; }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
