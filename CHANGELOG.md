# Development history / changelog

This mod was built collaboratively with Claude (Anthropic's AI) through an
extended series of implementation passes and real bug fixes on a live
server. This file is that history, kept as-is rather than cleaned up,
because it's genuinely useful context: several of these were real,
confirmed exploits (not theoretical), found through actual multiplayer
use and fixed the same way.

If you just want to know how to use the mod, see [README.md](README.md)
instead — this file is "what changed and why," not a user guide.

---

## 9. Fixed: leaving through a specific portal now returns to that portal

Previously, leaving via ANY creative-dim portal always sent the player back
to wherever they originally entered from (a single saved snapshot
position) -- meaning with two overworld portals, walking back through
portal 1 could incorrectly land you at portal 2's overworld position.

Fixed by having `PortalLinkRegistry` record each portal's *full* real
position (dimension + exact x/y/z, not just x/z) plus a direct reverse
mapping from each creative-dim analogous frame back to the specific
overworld portal it belongs to. Leaving through a given creative-dim
portal now resolves and teleports to that portal's own real overworld
spot; inventory/gamemode restoration still comes from the snapshot as
before, only the destination position changed. The admin `/creativedim
enter` anchor (which has no overworld counterpart) still falls back to
the original snapshot-based behavior.

## 10. Portal collapses when its frame is broken

Breaking any part of a lit frame's Amethyst Block border now correctly
clears the portal blocks filling its interior back to air, mirroring
vanilla's Nether portal collapse behavior. Works via `neighborChanged` --
whenever a block adjacent to a portal cell changes, it flood-fills the
connected portal region and checks whether the Amethyst ring around it is
still fully intact; if not, the whole interior clears in one pass.

This runs identically in both directions -- breaking an overworld frame
clears that portal, and breaking a creative-dim analogous frame clears
that one too. It does NOT currently cascade across dimensions (breaking
the overworld frame doesn't also clear its creative-dim counterpart, or
vice versa) -- each side's portal only reacts to its own frame breaking.
That's a reasonable follow-up if it turns out to matter in practice.

## 11. Fixed: portal wouldn't light after adding frame-breakage detection

The breakage check added in section 10 caused a self-inflicted regression:
`fill()` placed portal blocks one at a time using `setBlockAndUpdate`,
which notifies neighbors as it goes -- so placing the 2nd cell notified
the 1st, triggering the new breakage check while the interior was still
mid-construction (mostly air). It looked broken because it genuinely was,
momentarily, by design of the loop. Fixed by using `setBlock(pos, state, 2)`
(update clients only, skip neighbor notification) during all frame/portal
construction, so the breakage check only ever runs in response to a real
external change (a player breaking a block), not our own build-out.

## 12. Fixed: Curios accessory slots weren't part of the inventory swap

Real exploit: Curios (rings, belts, bags, etc.) lives in a completely
separate inventory system from vanilla's main/armor/offhand slots, so the
original inventory swap never touched it. A player could stash items in a
Curios slot before entering, pick up MORE items into a Curios-slot bag
while inside the creative dimension, and walk out with everything intact
since nothing ever cleared or restored Curios contents.

Fixed via `CuriosIntegration.java`, using `CuriosApi.getCuriosInventory`,
`ICuriosItemHandler#saveInventory`/`loadInventory`, and the standard
`IItemHandlerModifiable` interface -- all confirmed via `javap` against
the real `curios-neoforge-9.5.1+1.21.1.jar`. One deliberate design choice:
`saveInventory(boolean)` takes an undocumented flag I couldn't confirm the
exact meaning of (possibly "also clear as part of saving"), so rather than
gamble on it, this uses `saveInventory(false)` (a guaranteed plain save)
followed by manually zeroing every slot through the well-established
`IItemHandlerModifiable#setStackInSlot` interface instead.

Curios data now rides along in `PlayerSnapshot` and persists through
`SnapshotStore` the same as everything else, so a server restart mid-visit
doesn't lose it either.

**Curios is treated as a required dependency** in `build.gradle` (not
optional like the other three), since it's confirmed always present in
this modpack. If you ever run this mod without Curios installed, it will
fail to load -- that's a deliberate tradeoff for this specific server, not
a general-purpose default.

## 13. Creative inventory loadout (QoL)

Your creative-mode inventory now persists across visits, per player.
Whatever you had in your main inventory/armor/offhand when you leave the
creative dimension gets saved as your personal "loadout" -- captured right
before the existing anti-cheat wipe that already happens on leave, so
nothing about the exploit-prevention changed, it just remembers what would
otherwise have been silently discarded. The next time you enter, that
loadout is restored automatically instead of starting from an empty
inventory.

- Deliberately separate from `PlayerSnapshot` (which handles your
  *survival* state) -- `CreativeLoadout` only covers what you're given
  back on *entry*.
- Curios accessory slots are NOT part of the loadout, only main
  inventory/armor/offhand -- matches what's actually meant by "creative
  inventory setup" here.
- Persists across restarts the same way everything else does
  (`world/sablecreativedim/creativedim_loadouts.dat`), write-through on
  every leave.
- If you've never left the creative dimension before (fresh player), entry
  behaves exactly as before -- empty inventory, nothing to restore.

## 14. Fixed: Ender Chest exploit (confirmed real, not just theoretical)

Ender Chest contents are tied to the player, not the block or location --
completely separate from our inventory snapshot system, so opening one
while standing in the creative dimension gave full access to real
survival storage. Confirmed as an actual working exploit before this fix
went in (not just a theoretical concern like some of the others flagged).

Fixed via `CreativeDimRestrictionsHandler`, which blocks right-click
interaction with Ender Chests specifically while inside the creative
dimension. The block itself is untouched -- this cancels the interaction,
it doesn't need to snapshot/clear/restore anything the way Curios did,
since an Ender Chest's contents never actually enter the player's
inventory at all.

Worth keeping in mind: this is really "any storage mechanism tied to the
player/globally rather than to a location," and Ender Chest is just the
concrete case that got found and confirmed. Other global storage in this
modpack (an ender-linked backpack upgrade, a cross-dimension ME network
terminal, etc.) could have the same category of issue -- not covered by
this fix, since nothing else has been reported or tested yet.
