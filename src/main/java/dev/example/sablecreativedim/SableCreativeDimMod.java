package dev.example.sablecreativedim;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Registers a real, server-side dimension ("creative_testing") intended for
 * assembling/testing Sable + Create Aeronautics contraptions in creative
 * mode without leaving the shared world/server.
 *
 * The dimension itself is fully datapack-defined (see
 * data/sablecreativedim/dimension/creative_testing.json and the matching
 * dimension_type json) -- this class just exposes a typed ResourceKey other
 * classes reference, and wires up the command, portal block, frame
 * activation handler, and (client-side only) the portal-charge shimmer.
 */
@Mod(SableCreativeDimMod.MODID)
public class SableCreativeDimMod {

    public static final String MODID = "sablecreativedim";

    /** ResourceKey for the dimension defined in data/sablecreativedim/dimension/creative_testing.json */
    public static final ResourceKey<Level> CREATIVE_TESTING = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(MODID, "creative_testing")
    );

    public SableCreativeDimMod(IEventBus modEventBus) {
        ModBlocks.BLOCKS.register(modEventBus);
        modEventBus.addListener(this::onRegisterPayloads);

        // Register on the game (not mod) bus, since these react to runtime
        // events (commands, player interactions, server lifecycle).
        NeoForge.EVENT_BUS.register(new SableTestCommand());
        NeoForge.EVENT_BUS.register(new FrameActivationHandler());
        NeoForge.EVENT_BUS.register(new CreativeDimRestrictionsHandler());

        // ClientPortalOverlay registers itself via @EventBusSubscriber(value
        // = Dist.CLIENT) -- no manual registration needed here, and no risk
        // of touching client-only classes on a dedicated server.
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MODID).versioned("1");
        registrar.playToClient(
                PortalChargePayload.TYPE,
                PortalChargePayload.STREAM_CODEC,
                // playToClient handlers only ever run on the client, so
                // touching ClientPortalOverlay here is safe even though
                // this method itself is loaded on both sides.
                (payload, context) -> context.enqueueWork(() -> ClientPortalOverlay.setProgress(payload.progress()))
        );
    }
}
