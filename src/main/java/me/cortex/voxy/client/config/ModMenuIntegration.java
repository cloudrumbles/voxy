package me.cortex.voxy.client.config;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.screen.ConfigCorruptedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.embeddedt.embeddium.api.OptionGUIConstructionEvent;
import org.embeddedt.embeddium.gui.EmbeddiumVideoOptionsScreen;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Connects Voxy's configuration page to Embeddium and Forge's mod-list
 * configuration button without relying on Fabric Mod Menu internals.
 */
public final class ModMenuIntegration {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Component VOXY_PAGE_TITLE = Component.translatable("voxy.config.title");

    private ModMenuIntegration() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }

        OptionGUIConstructionEvent.BUS.addListener(event -> {
            if (!VoxyCommon.isAvailable()) {
                return;
            }

            OptionPage page = VoxyConfigScreenPages.page();
            VoxyConfigScreenPages.voxyOptionPage = page;
            event.addPage(page);
        });
    }

    public static Screen createConfigScreen(Screen parent) {
        if (!VoxyCommon.isAvailable()) {
            return parent;
        }

        Supplier<Screen> screenFactory = () -> {
            selectVoxyPage();
            return new EmbeddiumVideoOptionsScreen(parent);
        };

        return SodiumClientMod.options().isReadOnly()
                ? new ConfigCorruptedScreen(screenFactory)
                : screenFactory.get();
    }

    /**
     * Embeddium remembers the selected page in a private static reference. Set
     * it before construction so Forge's Voxy config button opens on Voxy's
     * page, matching the original Mod Menu integration.
     */
    @SuppressWarnings("unchecked")
    private static void selectVoxyPage() {
        try {
            Field selectedTabField = EmbeddiumVideoOptionsScreen.class.getDeclaredField("tabFrameSelectedTab");
            selectedTabField.setAccessible(true);
            AtomicReference<Component> selectedTab =
                    (AtomicReference<Component>) selectedTabField.get(null);
            selectedTab.set(VOXY_PAGE_TITLE);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            Logger.warn("Unable to preselect the Voxy configuration page; opening Embeddium's default page instead");
        }
    }
}
