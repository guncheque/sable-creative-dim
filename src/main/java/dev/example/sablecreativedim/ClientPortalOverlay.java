package dev.example.sablecreativedim;

import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Renders a translucent, pride-colored horizontal-band shimmer over the
 * screen while the player is charging up inside a portal -- our version of
 * vanilla's purple screen tint while standing in a Nether portal. Driven
 * entirely by PortalChargePayload updates from the server; this class only
 * tracks the last progress value it was told and fades it out if updates
 * stop arriving (e.g. the player stepped out before finishing the charge).
 *
 * Two things layered on top of the raw progress value so it doesn't read
 * as a flat, static overlay: a smoothstep easing curve (so the fade feels
 * continuous across the whole charge duration instead of a linear ramp
 * that visually "arrives" early and then seems to do nothing), and a
 * gentle per-band sine-wave brightness pulse driven by an internal
 * animation clock, so the bands genuinely shimmer rather than just
 * sitting there getting more opaque.
 *
 * @EventBusSubscriber(value = Dist.CLIENT) tells NeoForge to skip loading
 * this class entirely on a dedicated server -- confirmed to work in this
 * exact project already (used successfully elsewhere), unlike the
 * FMLEnvironment.dist.isClient() check this replaces, which turned out to
 * reference a class that doesn't actually exist in this NeoForge build.
 * The `bus` parameter is intentionally omitted since it's deprecated;
 * NeoForge infers the correct bus from each event's own type.
 */
@EventBusSubscriber(modid = SableCreativeDimMod.MODID, value = Dist.CLIENT)
public final class ClientPortalOverlay {

    private static volatile float progress = 0f;
    private static volatile int ticksSinceUpdate = Integer.MAX_VALUE;
    private static volatile int animTick = 0;

    private static final int[] PRIDE_COLORS = {
            0xE40303, 0xFF8C00, 0xFFED00, 0x008026, 0x004CFF, 0x732982
    };

    private ClientPortalOverlay() {}

    public static void setProgress(float value) {
        progress = value;
        ticksSinceUpdate = 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        animTick++;
        ticksSinceUpdate++;
        if (ticksSinceUpdate > 5) {
            progress = 0f;
        }
    }

    /** Smoothstep: 0 and 1 at the ends, continuously accelerating through the middle -- reads as a genuinely gradual fade rather than a linear ramp that plateaus perceptually. */
    private static float smoothstep(float t) {
        return t * t * (3f - 2f * t);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Pre event) {
        if (progress <= 0f) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        float eased = smoothstep(progress);
        int baseMaxAlpha = (int) (120 * eased);
        int bandHeight = height / PRIDE_COLORS.length + 1;
        double phase = animTick * 0.12;
        for (int i = 0; i < PRIDE_COLORS.length; i++) {
            // Each band pulses slightly out of sync with its neighbors so
            // the shimmer reads as motion across the bands, not a single
            // uniform flicker.
            float shimmer = 0.75f + 0.25f * (float) Math.sin(phase + i * 0.9);
            int alpha = Math.max(0, Math.min(255, (int) (baseMaxAlpha * shimmer)));
            int color = (alpha << 24) | (PRIDE_COLORS[i] & 0x00FFFFFF);
            int y1 = i * bandHeight;
            int y2 = Math.min(height, y1 + bandHeight);
            graphics.fill(0, y1, width, y2, color);
        }
    }
}
