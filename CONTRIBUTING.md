# Contributing

Source is open and pull requests are welcome. A few notes on scope and
expectations before you dig in:

## What's welcome

- Bug fixes, especially anything touching the areas flagged as
  known-uncertain in the code comments or [CHANGELOG.md](CHANGELOG.md)
  (a few spots were written from best-guess API signatures and later
  confirmed correct via `javap` against the real jars -- others may
  still have edge cases).
- Compatibility fixes for other Create Aeronautics / Sable versions --
  this has only been tested against the exact versions listed in the
  README, and hasn't been built against anything newer.
- Documentation fixes, typos, clearer setup instructions.

## What's likely out of scope

- New features beyond what's already here. This mod was built to solve
  a specific problem (testing physics contraptions without leaving
  survival) for a specific server, and the maintainer's own support
  bandwidth is limited to keeping it working, not extending its feature
  set on request. If you want new functionality, forking is genuinely
  the right move here, not a feature-request PR.

## Before you open a PR

- Test on a real server if the change touches gameplay behavior --
  several bugs in this project's history only showed up under actual
  multiplayer use, not in isolated testing (see CHANGELOG.md for
  examples).
- If you're touching NeoForge/Minecraft API calls you're not fully sure
  about, `javap` against the real jars is the fastest way to confirm a
  signature rather than guessing -- see the pattern used throughout this
  project's development in CHANGELOG.md.
- Keep the exploit-prevention behavior intact (inventory wipe on leave,
  Curios handling, Ender Chest blocking) unless a PR is specifically
  about fixing a related bug -- these exist because real exploits were
  found and confirmed through live testing, not as a theoretical
  precaution.
