package dev.example.sablecreativedim;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client payload carrying current portal-charge progress (0.0 to
 * 1.0), used to drive the rainbow shimmer overlay while a player is
 * standing in a portal charging up (like vanilla's purple screen tint
 * while standing in a Nether portal).
 *
 * VERSION UNCERTAINTY: this is the single least-verified piece of code in
 * the whole mod. NeoForge's custom-payload networking API
 * (CustomPacketPayload, StreamCodec, RegisterPayloadHandlersEvent) is the
 * modern 1.20.5+ replacement for the older SimpleChannel system, but I
 * don't have the real jars to compile-test this against. If this doesn't
 * build, paste the error same as everything else in this project -- fixing
 * from a real compiler error is much faster than guessing further.
 */
public record PortalChargePayload(float progress) implements CustomPacketPayload {

    public static final Type<PortalChargePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SableCreativeDimMod.MODID, "portal_charge"));

    public static final StreamCodec<ByteBuf, PortalChargePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, PortalChargePayload::progress,
            PortalChargePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
