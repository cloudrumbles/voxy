package me.cortex.voxy.client.core.model.berbake;

import net.minecraft.client.renderer.Sheets;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * BER-bake handler for vanilla banners (standing + wall, all 16 colours).
 *
 * A banner's geometry is the cloth + pole/bar drawn by BannerRenderer onto the banner
 * atlas ({@link Sheets#BANNER_SHEET}), but its COLOUR is not in the texture — the cloth
 * is a white base that the renderer tints per-vertex by the banner's DyeColor (and the
 * pattern layers, which need the BlockEntity NBT we don't have at LOD). voxy's vertex
 * consumer discards that per-vertex tint, so a plain capture yields a white banner.
 *
 * We therefore: (a) capture the real renderer (cloth + pole, correct orientation from
 * FACING/ROTATION) on the banner atlas, and (b) supply the banner's base DyeColor as a
 * constant tint ({@link #constantTint}), which the bakery multiplies over the white
 * capture. Result: a correctly-coloured, correctly-shaped banner at LOD. Patterns are
 * unrecoverable without the BlockEntity and are intentionally not represented.
 *
 * Registered for {@link AbstractBannerBlock} (covers all vanilla banner blocks). Modded
 * banners that don't extend it (custom single-design banners) are out of scope.
 */
public final class BannerBerBakeHandler extends AbstractBerBakeHandler {
    @Override
    public ResourceLocation atlas() {
        return Sheets.BANNER_SHEET;
    }

    @Override
    public int constantTint(BlockState state) {
        if (state.getBlock() instanceof AbstractBannerBlock banner) {
            DyeColor dye = banner.getColor();
            float[] rgb = dye.getTextureDiffuseColors();
            int r = Math.round(rgb[0] * 255f) & 0xFF;
            int g = Math.round(rgb[1] * 255f) & 0xFF;
            int b = Math.round(rgb[2] * 255f) & 0xFF;
            return (r << 16) | (g << 8) | b;//ARGB alpha added by the bakery
        }
        return -1;
    }
}
