package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.iris.ShaderLoadError;
import me.cortex.voxy.common.Logger;
import net.coderbot.iris.Iris;
import net.coderbot.iris.shaderpack.ProgramSet;
import net.coderbot.iris.shaderpack.ShaderPack;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = Iris.class, remap = false)
public class MixinIris {
    @Redirect(
            method = "createPipeline",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/coderbot/iris/shaderpack/ShaderPack;getProgramSet(Lnet/coderbot/iris/shaderpack/materialmap/NamespacedId;)Lnet/coderbot/iris/shaderpack/ProgramSet;"))
    private static ProgramSet voxy$handleInvalidPatch(ShaderPack shaderPack, NamespacedId dimension) {
        try {
            return shaderPack.getProgramSet(dimension);
        } catch (ShaderLoadError exception) {
            Logger.error("Could not create the Oculus/Voxy shader pipeline", exception);
            return null;
        }
    }
}
