# Player report 2026-11-03

Reported against `release/v0.7.0` (9a9ab89). Logic items checked with `tools/why_rule.py` (BACAP, every
lock). Tick an item once it's verified on 26.1.2, 26.2 and 26.3.

Branches: `fix/goal-boss-persistence`, `fix/locked-structure-spawns`, `fix/nether-portal-bypass` (mod),
`fix/report-2026-11-03-logic` (apworld).

---

## Mod

- [ ] **G1. Goal never fires after a restart** (`fix/goal-boss-persistence`)
  `GoalTracker.killedBosses` / `killedDeathListMobs` are static in-memory sets. A boss killed in an
  earlier server session is forgotten, so finishing the advancements later never satisfies
  `killedBosses.containsAll(bossSelection)`.
  Fix: also count a boss / death-list mob as killed when any online player's vanilla
  `minecraft:killed` stat for it is > 0 (stats persist in the save). `evaluate()` already runs on join.

- [ ] **S1. Structure mobs spawn inside a locked structure's bounds** (`fix/locked-structure-spawns`)
  Guardians (monument), blazes / wither skeletons (fortress) come from structure `spawn_overrides`.
  `ChunkGenerator.getMobsAt` finds them through `StructureManager.getAllStructuresAt`, and the
  fortress-floor rule through `getStructureAt` — neither is wrapped by `StructureManagerMixin`, which
  only hides locked starts from `getStructureWithPieceAt`.
  Fix: wrap both in the same mixin (live `ServerLevel` only): drop locked structures from the map,
  return `INVALID_START` for a locked one.

- [ ] **S2. `monster_room_deep` is never locked** (same branch)
  Dungeons below y=0 are the separate placed feature `minecraft:monster_room_deep`; the lock registry
  only knows `minecraft:monster_room`. Fix: `StructureLockService.lockedFeatureId` maps the deep
  feature to `monster_room`, so it captures under the same unlock.

- [ ] **N1. Nether reachable through a portal by ender pearl or vehicle** (`fix/nether-portal-bypass`)
  `EntityMixin.canTeleport` only gates `Player`. A boat/minecart with a player in it, or a thrown
  pearl (which then pulls its owner across), teleports as a non-player.
  Fix: also gate an entity carrying a player passenger and a `ThrownEnderpearl` owned by a player.

## Logic (`fix/report-2026-11-03-logic`)

- [ ] **L1. "And make it double!" doesn't need a jukebox.** The criterion's `DuplicationCooldown` nbt
  is unread, so it's just "allay + amethyst". Fix: `_EXTRA_REQUIREMENT` → `can_duplicate_allay()`, and
  that helper now acquires the jukebox (crafting + diamond) instead of the bare diamond tier.
- [ ] **L2. "Battle of the Bands" doesn't need a raid.** `minecraft:impossible` criterion (granted by
  `check/battle_of_the_bands`: blow a goat horn with a raider in an active raid nearby), so it falls back
  to its parent. Fix: `_EXTRA_REQUIREMENT` → `can_raid()` + goat horn.
- [ ] **L3. "Chestful of Cobblestone" = "MOAR Tools".** Same impossible-criterion fallback. Datapack:
  1728 cobblestone in a chest, stand on it. Fix: `_EXTRA_REQUIREMENT` → chest + cobblestone.
- [ ] **L4. "Pyrotechnic" ignores the shapes.** `minecraft:fireworks` item predicate is unread. Fix in
  `criteria._item`: explosions → firework star + shape ingredient (large_ball fire charge, star gold
  nugget, creeper a mob head, burst feather), `has_twinkle` glowstone dust, `has_trail` diamond.
- [ ] **L5. "Noteworthy" only needs a note block.** Location `block.state.instrument` is unread. Fix:
  map each instrument to the block under the note block (vanilla `NoteBlockInstrument`).
- [ ] **L6. Named items/mobs don't need an anvil** ("I just want to make toast!", "I'll be back",
  "This name sounds cooler"). Still broken. `minecraft:custom_name` component is unread. Fix: it asks
  for the anvil (which carries Knowledge: Anvil).
- [ ] **L7. Cobweb.** Not free in the Overworld anymore (palette fix), but it still counts villages and
  mansions. Narrowed to where it's abundant: mineshafts, strongholds, trial chambers.
- [ ] **L8. `mushroom_stem` matches `_WOOD_RE`** (5e71818), so it's free in the Overworld and "Mega
  Mushroom" is sphere 1. Fix: exclude it from the regex.

## Check

```
python tools/why_rule.py --mc <v> "And make it double" "Battle of the Bands" "Chestful of Cobblestone" \
    "Pyrotechnic" "Noteworthy" "I just want to make toast" "I'll be back" "This name sounds cooler" \
    "Eeuuwww" "Mega Mushroom"
python tools/check_compile_all.py
./gradlew build   (all three versions) + tools/check_mixin_targets.py
```
In-game: G1 (kill dragon, restart, finish last advancement), S1 (monument before unlock), N1 (boat +
pearl into a portal with the Nether locked), S2 (dungeon below y=0).
