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

## 15. Updated to Sable 2.0.3 / Create Aeronautics 1.3.0 -- real risk, not routine

Bumped from Sable 1.2.2 to **2.0.3** and Create Aeronautics 1.2.1 to
**1.3.0**. These two updates are NOT the same risk level, worth treating
differently:

**Create Aeronautics 1.3.0** looks like a clean, purely additive minor
release (new Gyroscopic Mechanism texture, FE transfer on Docking
Connectors) -- low risk.

**Sable 2.0.0** (which 2.0.3 sits on top of, per its own changelog) was a
real major-version jump with genuinely concerning items in its own
changelog: "Update NeoForge version," "Update the physics constraint
API," and specifically "Mark some internal Sable classes as internal."
That last one matters less than it might sound for this specific mod --
`sable-creative-dim`'s own Java code never directly imports any
`dev.ryanhcode.sable.*` classes; Sable just observes our custom dimension
transparently the same way it observes the overworld, which is exactly
what the earlier `DimensionPhysics.createDefault(Level)` research (see
README section on dimension setup) confirmed is dimension-agnostic by
design. So a major Sable version bump is lower-risk for *this* mod than
it would be for something that actually calls into Sable's API surface
directly (like the bridging-mod project explored separately).

Still explicitly untested as of this entry -- this update was made
without the ability to compile-test or run the actual mod (same sandbox
network limitation as always). The `versionRange` in `neoforge.mods.toml`
was deliberately kept tight (`[2.0.3,)`, not loosened to something like
`[2.0.0,)`) specifically because of the major-version risk -- don't widen
it without actually testing against whatever you're widening it to.

One incomplete item from this update, since resolved: Sable's exact
Modrinth version ID for 2.0.3 couldn't be pinned down through search
(Modrinth's versions list is JS-rendered), so `build.gradle` briefly had
a placeholder there. Confirmed real value:
`maven.modrinth:T9PomCSv:1L6XJqnY`.

## 16. Fixed: intermittent fall-through-then-correct on leaving the creative dimension

Reported symptom: occasionally, leaving the creative dimension would drop
the player briefly through the world before they got teleported back up
to the correct surface position -- intermittent, not every time.

This is a known class of bug with cross-dimension teleports generally:
`teleportTo(...)` can place a player at target coordinates before that
chunk has actually finished loading server-side, so gravity applies
against a not-yet-real world for a moment, and the player falls until the
chunk catches up and the server corrects their position. The
intermittency matches this exactly -- it only shows up when the
destination chunk wasn't already loaded/cached at the moment of teleport.

Fixed by adding `ensureChunkLoaded(...)` (in `CreativeDimTeleporter.java`),
called right before every `teleportTo(...)` call in both `enter()` and
`leave()`. It calls `ServerLevel#getChunk(int, int)` without an extra
status argument, which blocks until the chunk is genuinely loaded, turning
the race into a guaranteed ordering instead of a timing gamble. Applied to
both directions (entering and leaving) even though it was only reported on
leave, since the underlying cause applies equally to either teleport.

## 17. Fixed: real exploit -- Curios items equipped mid-visit could survive back into survival

Reported symptom: goggles in the Curios head slot sometimes ended up in
the player's survival inventory after leaving the creative dimension.

Root cause: `CuriosIntegration.restore(...)` called `loadInventory(data)`
on the way out, but nothing ever explicitly cleared the player's CURRENT
Curios state first -- unlike the main inventory, which already has an
explicit `clearContent()` wipe before its own restore. Our saved Curios
data only records non-empty slots (same sparse-save convention as
`ItemStackNbtUtil`), so if a slot was empty at snapshot time, there was
nothing in the saved data to overwrite a NEW item equipped into that same
slot during the visit -- it just survived the "restore" untouched. Same
class of exploit as the original Curios bug (section 12), just missing
one explicit step that main-inventory restore already had.

Fixed by adding `CuriosIntegration.clearAll(player)` -- a plain "empty
every slot, save nothing" operation -- called immediately before
`restore(...)` in `CreativeDimTeleporter.leave()`, mirroring exactly how
`player.getInventory().clearContent()` already works for the main
inventory. Worth testing deliberately: enter with an empty Curios head
slot, equip something into it while inside, leave, confirm it's gone
(not carried to survival) and whatever was originally there (if
anything) is correctly back.

## 18. Curios now included in the creative loadout (feature request)

The original creative loadout system (section 13) deliberately excluded
Curios accessory slots, only covering main inventory/armor/offhand. After
real use, that turned out to be a genuine gap rather than the right
scope -- players legitimately want their creative-mode accessories
(e.g. goggles) to persist between visits the same way their tools/blocks
already do.

Added via a new `CuriosIntegration.saveOnly(player)` (non-destructive --
captures current Curios state without clearing anything, distinct from
`stashAndClear`, which always clears as part of saving and exists for the
separate survival-snapshot purpose). `CreativeLoadout` now carries a
`curios` field alongside the existing inventory data, captured at the
same point in `leave()` as the rest of the loadout (before any clearing
happens), and restored in `enter()` right after the main loadout is
applied -- safe to restore directly with no extra clear step needed
there, since `stashAndClear()` earlier in the same call already emptied
every Curios slot for the survival-snapshot purpose.

## 19. 50-block-thick floor (for tunnel bore testing)

Swapped the flat-world generator's floor from a single grass block over
void to 49 blocks of stone capped with 1 block of grass -- 50 total
blocks of diggable depth, so tunnel bore contraptions have real material
to chew through instead of hitting void one block down. Pure config
change (`dimension/creative_testing.json`), no Java touched -- the total
height (65 blocks: air + solid) is unchanged from before, only the split
between air and solid changed, so the walkable surface sits at the exact
same world Y as always and every hardcoded position (entry point,
auto-built return portals) is still valid without modification.

Same caveat as every previous floor-material change: world generation is
baked in per-chunk once generated, so this only affects chunks that
don't already exist. Two ways to actually get the new floor: delete
`world/dimensions/sablecreativedim/creative_testing` for a clean
regenerate (loses anything already built there), or just build/test in
chunks you haven't visited yet in that dimension -- those will generate
fresh with the new 50-block floor without touching what's already there.

## 20. Corners now optional, matching vanilla Nether portal behavior

Real root cause found for the "custom portal sizes don't light" report:
the frame verification required all 4 exact corners to be Amethyst Block,
which vanilla Nether portals never require -- vanilla only checks the
straight edges, letting corners be empty/diagonal. Not a bug exactly, but
a real behavioral gap from vanilla that was surprising in practice.

Fixing this needed two coordinated changes, not one: `floodFillAmethyst`
now traverses 8 directions (orthogonal AND diagonal) instead of just 4,
since two edges meeting at a genuinely missing corner only touch
diagonally -- purely-orthogonal flood fill could never have discovered
them as one connected shape to begin with. Separately, the verification
loops in both `findFrame` and the breakage-detection
`checkAndClearIfBroken` now explicitly skip the 4 exact corner positions
rather than requiring them, so a validly-lit corner-less frame doesn't
get incorrectly cleared the next time something nearby changes. Also
removed the temporary diagnostic logging added while investigating this
-- no longer needed now that the real cause is confirmed and fixed.
