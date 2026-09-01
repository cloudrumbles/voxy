package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class MixinWindow {
    /*
     * Mixin 0.8.5 only permits constructor injection after the object has been
     * fully initialised. The newer upstream hook targeted an invocation inside
     * <init>, which is rejected on Forge 1.19.2. Running at RETURN preserves
     * the intended render-thread priority change without touching an
     * uninitialised Window instance.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$afterInitWindow(
            WindowEventHandler eventHandler,
            ScreenManager screenManager,
            DisplayData displayData,
            String fullscreenVideoModeString,
            String title,
            CallbackInfo ci
    ) {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
    }
}
