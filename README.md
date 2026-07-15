# Sable Creative Dimension

A NeoForge addon that registers a real, server-side creative-mode dimension
scoped for building/testing Sable + Create Aeronautics contraptions, entered
and left via `/creativedim enter` / `/creativedim leave`. Replaces the "Plan"
mod, which didn't produce real physics-compatible blocks/entities.

**This project is built directly from NeoForge's own official MDK**
(`neoforged/MDK`, `archive/1.21` branch -- the correct one for Minecraft
1.21.1) rather than hand-assembled Gradle config. Earlier drafts of this
project guessed at Gradle/plugin versions and got several details wrong;
this version uses the actual files NeoForge ships, so the wrapper, plugin
version, and run configuration are all known-working rather than inferred.

No code from Create Aeronautics/Simulated-Project or Sable is copied here --
Aeronautics is "all rights reserved," so this only *depends on* their jars
at compile/runtime like any other NeoForge addon would.

---
**This mod was built collaboratively with Claude (Anthropic's AI)**, through
an extended back-and-forth of implementation, live testing, and real bug
fixes on an actual server -- not a one-shot generation. I'm stating that
plainly rather than passing the code off as entirely hand-written. It's
been through genuine debugging, including two confirmed real exploits
(not just theoretical) found through actual multiplayer use and fixed the
same way -- see [CHANGELOG.md](CHANGELOG.md) for the full history if
you're curious how any of this came together.

---

## 1. Requirements on Arch (fish shell)

Just JDK 21 -- the project's own `gradlew` handles Gradle itself (it will
download Gradle 8.14.2 automatically on first run, pinned in
`gradle/wrapper/gradle-wrapper.properties`):

```fish
sudo pacman -S jdk21-openjdk
sudo archlinux-java set java-21-openjdk
java -version
```

You do **not** need a system-wide `gradle` package for this project -- if
you installed one earlier while troubleshooting, it's safe to leave it or
remove it (`sudo pacman -R gradle`), it won't be used here.

## 2. Clone the project

```fish
mkdir -p ~/dev
cd ~/dev
git clone https://github.com/<your-username>/sable-creative-dim.git
cd sable-creative-dim
chmod +x gradlew
```

(Swap `<your-username>` for wherever this actually ends up hosted.)

## 3. Dependencies (automatic)

This mod depends on four other mods (Create, Sable, Create Aeronautics,
Curios), all resolved automatically from Modrinth's Maven repository --
no manual downloading, no `libs/` folder to populate. `./gradlew build`
in the next step just works.

Pinned to the exact versions this mod has actually been built and tested
against:

| Mod | Version | Modrinth page |
|---|---|---|
| Create | 6.0.10 | https://modrinth.com/mod/create |
| Sable | 1.2.2 | https://modrinth.com/mod/sable |
| Create Aeronautics | 1.2.1 | https://modrinth.com/mod/create-aeronautics |
| Curios API | 9.5.1+1.21.1 | https://modrinth.com/mod/curios |

Every coordinate in `build.gradle` was independently verified against
each mod's real Modrinth version page (project ID, version ID, and
filename all cross-checked) rather than guessed -- see CHANGELOG.md for
why that verification habit exists in this project specifically.

**This mod has only been built and tested against these exact versions**
-- other versions of these four mods may or may not work. To target a
different version, find its Version ID on Modrinth's page for that
release (visible under "Metadata" → "Version ID") and swap the coordinate
in `build.gradle`.

One thing I couldn't verify: Aeronautics' actual internal `modId` string,
used in `neoforge.mods.toml`'s optional-dependency block (I guessed
`create_aeronautics`). If the build or server startup complains about that
specific entry, unzip the Aeronautics jar and check its own
`META-INF/neoforge.mods.toml` for the real id, or just delete that block --
it's optional and only affects load ordering.

## 4. Build / test

```fish
./gradlew build
```

First run downloads Gradle itself plus decompiles/patches Minecraft -- slow
(several minutes), subsequent runs are fast. Output jar lands in
`build/libs/`.

Since this is for a dedicated server, test with:

```fish
./gradlew runServer
```

This uses `run/` as a scratch server directory and, thanks to `localRuntime`,
will actually load Create/Sable/Aeronautics/Curios alongside your mod.

Once you're happy with a build, take the jar from `build/libs/` and drop it
into the real server's `mods/` folder alongside the existing
Create/Sable/Aeronautics jars.

## 5. What's included

- `data/sablecreativedim/dimension_type/creative_testing.json` -- dimension
  type sized like the overworld, so Sable's physics defaults (which read
  sea level/height straight off the dimension type) come out sane.
- `data/sablecreativedim/dimension/creative_testing.json` -- the actual
  dimension: a void/flat world with a barrier floor.
- `SableCreativeDimMod.java` -- mod entrypoint, registers the dimension key
  and the command handler.
- `SableTestCommand.java` -- `/creativedim enter` / `/creativedim leave`, open to
  any player (no permission node required):
  - **enter**: snapshots position, gamemode, and full inventory (main +
    armor + offhand), empties the inventory, teleports in, sets creative +
    flight.
  - **leave**: wipes whatever's currently held (anti-cheat step -- nothing
    built/spawned in creative crosses back), teleports back to the exact
    spot, restores gamemode and the original inventory.
- `PlayerSnapshot.java` / `SnapshotStore.java` -- the snapshot data and its
  disk persistence. Snapshots are written to
  `world/sablecreativedim/creativedim_snapshots.dat` on every enter/leave
  (not just clean shutdown) and reloaded on server start, so a crash or
  restart mid-visit doesn't lose anyone's stashed inventory.

`PlayerSnapshot`'s NBT (de)serialization uses `ItemStack#save(HolderLookup.Provider)`
and `ItemStack.parseOptional(...)` -- confirmed correct against the real
1.21.1 `ItemStack` class via `javap` against the actual game jar.

## 6. Amethyst portal (build/light/step through)

- **Build a frame**: a hollow rectangle of Amethyst Block, minimum 2-wide
  by 3-tall interior (same minimum as a vanilla Nether portal), max 21x21.
  Can face either horizontal direction.
- **Light it**: right-click any block of the frame with Flint and Steel.
  If the frame is valid, the interior fills with a glowing portal
  (visually reuses vanilla's Nether portal shimmer/texture, so amethyst's
  purple tone carries through into the portal itself).
- **Step through**: walking into the portal surface triggers the exact
  same snapshot/teleport logic as `/creativedim enter` or `/creativedim
  leave` -- whichever direction makes sense based on which dimension
  you're physically standing in when you touch it.
- **The way back is automatic**: the first time anyone enters, a small
  fixed return portal is auto-built a few blocks from the entry point
  inside the creative dimension, so you're never stuck without a portal
  to walk back through.
- The command (`/creativedim enter` / `/creativedim leave`) still works
  exactly as before -- the portal is just a second way to trigger the
  same underlying logic (see `CreativeDimTeleporter.java`), not a
  replacement.

`FrameActivationHandler`'s `ItemStack#hurtAndBreak(int, LivingEntity, EquipmentSlot)`
call is confirmed correct against the real game jar as well.

One remaining genuine design tradeoff worth knowing about: the frame
detector (`AmethystFrameHelper`) uses a flood-fill + bounding-rectangle
check rather than a faithful port of vanilla's exact portal-shape
algorithm. It works fine for a normal hand-built rectangular frame, but
oddly-shaped amethyst clusters *near* a real frame could confuse it. If a
valid-looking frame doesn't light, that's the first place to look.

## 7. Portal anchoring (overworld is the source of truth)

- Building and lighting an amethyst frame in the **overworld** (or any
  dimension other than the creative test dimension) always works if the
  frame shape is valid. It also registers that position and auto-builds a
  matching portal at the same (x, z) inside the creative dimension --
  that's the "analogous coordinates" link.
- Building and lighting a frame **inside the creative dimension itself**
  only succeeds if that exact (x, z) is already a registered, real
  overworld anchor. Otherwise the frame simply won't light -- no message
  spam, no error, it just doesn't activate, mirroring how a random
  obsidian rectangle in vanilla doesn't become a portal either. If a
  *valid-shaped* frame fails to light for this reason specifically, the
  player gets a one-line explanation telling them to build the real one
  in the overworld first.
- Walking through an overworld portal lands you at that portal's specific
  analogous spot in the creative dimension -- multiple overworld portals
  now give players their own separate areas rather than all funneling
  into one shared space.
- `/creativedim enter` (operator only) still uses one fixed admin anchor,
  completely separate from this per-portal system -- handy for quick
  testing without needing to build anything.
- Portal links persist across restarts (`world/sablecreativedim/creativedim_portal_links.dat`),
  same write-through-and-reload pattern as player snapshots.
- Coordinates are scaled **8x** going into the creative dimension, matching
  vanilla's real Nether ratio but inverted -- a portal built at overworld
  (100, 65, 200) lands its analogous portal at creative-dim (800, 1, 1600).
  Two overworld portals 50 blocks apart end up 400 blocks apart in the
  void, giving everyone real breathing room without needing to travel far
  in the real world to get it. Adjust `PortalLinkRegistry.CREATIVE_DIM_SCALE`
  if you want a different ratio.

## 8. Entry delay + rainbow shimmer

- Portals now mirror vanilla's real entry delay: **4 seconds (80 ticks)**
  standing inside in survival mode, **instant (1 tick)** in creative --
  matching vanilla's `playersNetherPortalDefaultDelay` /
  `playersNetherPortalCreativeDelay` defaults (hardcoded rather than read
  from those gamerules, to avoid guessing at their exact registered keys).
  Stepping out before it finishes cancels the teleport, same as vanilla.
- While charging, the screen shows a translucent pride-colored horizontal
  shimmer that fades in with progress -- our equivalent of vanilla's
  purple portal screen tint.
- Fully built, tested, and confirmed working end to end: the packet
  registration (`RegisterPayloadHandlersEvent`, `PayloadRegistrar`,
  `PacketDistributor`) and client rendering
  (`RenderGuiEvent`/`ClientTickEvent`) were verified via `javap` against
  the real game jar, and the whole charge-and-shimmer flow has been
  tested live in-game. One real bug did surface and got fixed along the
  way: a standing player's hitbox overlaps more than one portal cell at
  once, so `entityInside` fires multiple times per server tick --
  `PortalChargeTracker` now only advances its counter once per real tick
  regardless of how many times it's called.


---

## Development history

This mod has a real bug-fix history worth reading if you're extending it
or curious how it got here — see [CHANGELOG.md](CHANGELOG.md). Two of
those were confirmed real exploits (not just theoretical): Curios
accessory slots and Ender Chests both let players bypass the inventory
separation between survival and creative before being caught and fixed
through actual multiplayer testing.

## Contributing

Source is open and PRs are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md)
for what's actually in scope.

## License

MIT — see [LICENSE](LICENSE). This covers this mod's own code only; it
does not grant any rights to Create, Sable, Create Aeronautics, or
Curios, all of which are separate projects under their own licenses (see
section 3 above, "Add the server's real mod jars," for links to each).
