package dev.example.sablecreativedim;

import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The portal block has no item form -- like vanilla's Nether portal, it's
 * only ever placed by AmethystFrameHelper when a frame is lit, never held
 * or placed directly by a player.
 *
 * Uses DeferredRegister.Blocks (NeoForge's block-specific registration
 * helper) rather than the generic DeferredRegister.create(...), since the
 * generic version returns DeferredHolder<Block, I> and can't be typed as
 * DeferredBlock<CreativeDimPortalBlock> directly -- that mismatch was the
 * actual build error.
 */
public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(SableCreativeDimMod.MODID);

    public static final DeferredBlock<CreativeDimPortalBlock> CREATIVE_DIM_PORTAL = BLOCKS.register(
            "creative_dim_portal",
            () -> new CreativeDimPortalBlock(CreativeDimPortalBlock.portalProperties())
    );

    private ModBlocks() {}
}
