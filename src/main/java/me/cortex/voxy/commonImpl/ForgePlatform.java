package me.cortex.voxy.commonImpl;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.LoadingModList;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Small boundary around Forge loader APIs.
 *
 * Mixin plugins are created before the normal mod list is fully constructed,
 * so mod detection must work against both the early loading list and the
 * runtime {@link ModList}.
 */
public final class ForgePlatform {
    private ForgePlatform() {
    }

    public static boolean isModLoaded(String modId) {
        try {
            var loading = LoadingModList.get();
            if (loading != null) {
                return loading.getModFileById(modId) != null;
            }
        } catch (Throwable ignored) {
        }

        try {
            return ModList.get().isLoaded(modId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static boolean isDedicatedServer() {
        return FMLLoader.getDist() == Dist.DEDICATED_SERVER;
    }

    public static Optional<Path> modRoot(String modId) {
        try {
            var loading = LoadingModList.get();
            if (loading != null) {
                var file = loading.getModFileById(modId);
                if (file != null) {
                    return Optional.of(file.getFile().getSecureJar().getRootPath());
                }
            }
        } catch (Throwable ignored) {
        }

        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getOwningFile().getFile().getSecureJar().getRootPath());
    }

    public static Optional<String> modVersion(String modId) {
        try {
            return ModList.get().getModContainerById(modId)
                    .map(container -> container.getModInfo().getVersion().toString());
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }
}
