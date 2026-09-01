package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides debug overlay lines for the F3 screen.
 * On 1.20.1, these are injected via mixin into the debug overlay rather than
 * through the DebugScreenEntries API (which doesn't exist in this version).
 */
public class DebugEntries {
    public static void init() {
        // No-op on Forge 1.20.1; debug lines are added via MixinDebugOverlay
    }

    public static List<String> getDebugLines() {
        List<String> lines = new ArrayList<>();

        if (!VoxyCommon.isAvailable()) {
            lines.add(ChatFormatting.RED + "voxy-" + VoxyCommon.MOD_VERSION);
            return lines;
        }

        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            lines.add(ChatFormatting.YELLOW + "voxy-" + VoxyCommon.MOD_VERSION);
            return lines;
        }

        VoxyRenderSystem vrs = null;
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr != null) vrs = ((IGetVoxyRenderSystem) wr).getVoxyRenderSystem();

        lines.add((vrs == null ? ChatFormatting.DARK_GREEN : ChatFormatting.GREEN) + "voxy-" + VoxyCommon.MOD_VERSION);

        instance.addDebug(lines);

        if (vrs != null) {
            vrs.addDebugInfo(lines);
        }

        return lines;
    }
}
