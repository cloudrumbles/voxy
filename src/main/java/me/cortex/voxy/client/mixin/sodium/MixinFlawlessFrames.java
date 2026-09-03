package me.cortex.voxy.client.mixin.sodium;

import org.embeddedt.embeddium.api.service.FlawlessFramesService;
import org.embeddedt.embeddium.util.sodium.FlawlessFrames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Embeddium 0.3.32-beta.90 for Minecraft 1.19.2 accidentally exposes its
 * development-only flawless-frames provider through META-INF/services. The
 * provider touches TestRegistry when instantiated, which registers a required
 * embeddium:gametests network channel and prevents an otherwise ordinary
 * client from joining servers that do not run Embeddium.
 *
 * <p>Filter only that exact leaked provider. Legitimate flawless-frames
 * integrations remain available and no Forge network policy is weakened.</p>
 */
@Mixin(value = FlawlessFrames.class, remap = false)
public abstract class MixinFlawlessFrames {
    private static final String EMBEDDIUM_GAMETEST_PROVIDER =
            "org.embeddedt.embeddium.impl.gametest.tests.TestFlawlessFramesService";

    @Redirect(
            method = "onClientInitialization",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/ServiceLoader;stream()Ljava/util/stream/Stream;"
            ),
            remap = false
    )
    private static Stream<ServiceLoader.Provider<FlawlessFramesService>> voxy$filterLeakedGameTestProvider(
            ServiceLoader<FlawlessFramesService> loader) {
        return loader.stream()
                .filter(provider -> !EMBEDDIUM_GAMETEST_PROVIDER.equals(provider.type().getName()));
    }
}
