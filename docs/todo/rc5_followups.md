# v0.7.0-rc.5 follow-ups (player report, 2026-10-24)

Reported against `release/v0.7.0` at rc.5 (b3df88e). The reporter compared the branch with their own
fixes for the playtest report. Logic items were checked with rule snapshots on 26.1.2, 26.2 and
26.3, with their playtest YAML (BACAP on, structures and mobs locked).

How to use this file: one branch per section (or per tightly-related group). Tick an item only once
its fix is verified on all three versions (`--mc 26.1.2`, `--mc 26.2`, `--mc 26.3`). Numbers 1-13
match the report.

```
python tools/why_rule.py [--mc 26.3] [--without "<item>"] "<advancement name prefix>" ...
```

---

## P. Performance regression: logic compile went from ~2 s to ~2 min

- [x] **P1. Strict rules are fully expanded in `fixed_point.py`** (done 2026-10-24, `perf/strict-expansion`)

  "AEMinecraft (Minecraft): compiling logic for 856 locations (Minecraft 26.3, BACAP)..." took under
  2 s with the reporter's YAML; it now takes close to 2 minutes.
  - `git bisect`: starts at **c6bbea2** (~1 min), and later commits make it worse.
  - Time is spent in `fixed_point.py`: strict rules get fully expanded, and the rules that became
    strict with the recent fixes are expensive to expand.
  - The reporter (MissingNoShiny) converted strict rules to **zero-suppressed decision diagrams**
    (ZDDs) to dedupe identical sub-routes and got the step down to **~3 s**, with room left.

  Fix: dedupe shared sub-routes instead of expanding them (ZDD or equivalent hash-consed set
  family). Check: time the step before/after with the same YAML on all three versions, and confirm
  slot_data is identical (same seed). Related: the rc.5 DNF memory fix (f20c603) touched the same
  code.

  **Done:** strict prices are ZDDs now (`fixed_point.py`). BACAP + every lock: compile 122 s → 3.5-4 s
  on all three versions, Generate ~13 s, peak 1.3 GB (was 3.2 GB). Strict location rules and every
  price + flag identical to rc.5 on 26.1.2 / 26.2 / 26.3 (checked in one process, as ZDDs). Also fixed
  on the way: a route that folded into the same interned node as a loose price read took its mark (a
  gold ingot route "was" the yellow bed's price). Marked reads are now a one-child Or during the
  computation. Only the `finders_off` snapshot moves: 9 checks lose a wrong flimsy mark, so they get
  looser. Self-check: `python tools/check_zdd.py`.

---

## B. Bugs

- [ ] **1. Bosses goal tile turns gray instead of blue on a released / collected boss kill (F37)**

  Where: `DataLogicProvider.goalBossesState` / `goalState`, `isEarned(String gameId)`.
  A target counts as "earned" when the client holds the advancement named by its game id, and an id
  the client has no advancement for counts as earned. A boss's game id is the mob's
  (`minecraft:ender_dragon`), never an advancement, so every checked boss counts as earned. A boss
  kill checked by a release, a collect or another world of the same slot turns "Bosses" gray, while
  its X/Y and the win condition only count what the run has earned. The Advancements tile is fine.
  Fix: gray when the goal tile's own advancement is done on the client (its progress is what the
  server grants from the earned count), blue when enough targets are only checked.

- [ ] **2. A crafting criterion costs the recipe AND the item's full price (F23)**

  Where: `criteria._price_recipe` returns recipe AND `acquire(item)`.
  `acquire()` keeps only an item's dependable routes. A recipe route through an ingredient only
  obtainable by flimsy routes is dropped by `_demote` whenever another dependable route exists, so
  the criterion asks for the recipe on top of that other route.
  Example: "The Lie" with Cow, Village, Material Handling x3, Structure Finder x3 and one inventory
  slot is out of logic: it also asks for a Trial Chambers or a villager selling a cake. Boned,
  Imbued Projectiles and Renewable Energy also change with the fix. F23 only *looks* done because
  c6bbea2 made skeleton bones dependable, which hid it for Boned.
  Fix: price the recipe plus the locks the mod puts on the item however it arrives (a tool's
  Handling Knowledge and Material Handling tier, a gated block's Knowledge, a raw material's
  Material Handling), not `acquire(item)`.

- [ ] **3. A block with a recipe that worldgen places is never a source of itself (F21, half done)**

  Where: `acquisition._acquire_from_sources`, the placed-only self-mine gate
  (`... and placed_only and base not in _NATURAL_SELF_MINED`).
  `_placed_block_origin` now reads `block_biomes.json`, but this gate only knows the hand-written
  `_NATURAL_SELF_MINED`, so mining such a block never yields the block itself (melon, snow and
  creaking_heart were added one by one; the rest are missing).
  Example: "Rock Collection" asks for smooth basalt smelted from Nether basalt (Furnace + Nether) or
  found in a structure, though every Overworld amethyst geode is lined with it. Same for badlands
  terracotta, coarse dirt, moss carpet, pale moss carpet, nether wart block, leaf litter, muddy
  mangrove roots, and sulfur on 26.2+.
  Fix: the gate asks "in `_NATURAL_SELF_MINED` **or** listed in `block_biomes.json`". Only adds
  routes; nothing gets stricter. Then see whether `_NATURAL_SELF_MINED` can be deleted.

- [ ] **4. Two BACAP advancements nobody can earn are still checks**

  Where: the BACAP location lists (`data.py` / `__init__.py`), next to the vanilla advancements BACAP
  deletes, which are already left out.
  - **Edge of the Jungle** (all three BACAP packs): only criterion is `minecraft:impossible`, nothing
    in the datapack grants it (biome removed in 1.18, kept for old worlds).
  - **Captain Etho** (`blazeandcave_1_21_1` only): should ask for a drowned holding a trident and a
    nautilus shell, but this pack checks for a vex, which spawns with an iron sword and never picks
    anything up. 1_20_3 and 1_21 are fine.
  A seed can put progression on either one, and then nobody can ever reach it.
  Fix: leave them out of the locations in those packs. Consider detecting `minecraft:impossible`-only
  advancements generically instead of listing names.

- [ ] **5. `structure_unlock` does not accept the group names the unlock items use**

  Where: `StructureUnlock.valid_keys` (options.py), `MCWorld._get_locked_structures` (`__init__.py`).
  With `split_structure_variants` off, the pool holds one "Structure Unlock: Village" /
  "Structure Unlock: Abandoned Camp" per group, but `structure_unlock` only accepts per-biome labels
  ("Village Plains", ...), a dimension or "All". "Village" is rejected, and listing "Village Plains"
  alone locks only the plains village while the item is "Structure Unlock: Village" and the other
  four villages are free from the start. Only matters for hand-listed YAMLs.
  Fix: accept the group names, and lock a whole group when any member is named.

- [ ] **6. Two criterion spellings are not read, and nothing fails loudly (against A11b)**

  Where: `criteria.py`: `_ITEM_KEYS` has no `"id"`; `_entity` / `_entity_type` have no BlockState
  parsing.
  An equipped item written as `{"id": "minecraft:tnt"}` prices nothing, and a `falling_block` entity
  is priced as an item nobody can obtain. Neither raises; they only land in the unread audit.
  "Stink Bomb" and "Classic Cartoon Comedy" (26.3) fall back to their parents' rules. Right only by
  luck (Uh Oh asks for `#sulfur_cube_archetype/explosive` = TNT only, Heavy Metal asks for an anvil).
  Fix: read `"id"` as an item key, parse BlockState for `falling_block` (ask for that block), or at
  least raise like the other unreadable predicates.

- [ ] **7. `base_color` is never read**

  Where: `criteria._COMPONENTS`.
  BACAP "Flying Colors" (1_21 and 1_21_1) asks for a shield with one of 16 `base_color` values, all in
  one requirement group; with the component unread the rule is just "a shield". In game it takes a
  shield plus a banner of that colour (wool, plus a dye unless white).
  Fix: price `base_color` as a banner of that colour applied to the shield, not as a dye.

- [ ] **8. With Silk Touch, glass, stained glass and panes read as natural blocks**

  Where: `acquisition._acquire_from_sources`, the silk_mining loop: a non-aged block takes
  `_natural_origin(block)` as origin (free unless rare-biome), never `_block_origin_node`, so neither
  the recipe nor the placing structures are asked for. `_placed_block_origin` also treats any block
  with a silk_mining source as natural.
  With Knowledge: Enchanting, Pickaxe Handling, Material Handling x5, a Structure Finder and a
  Shipwreck (only feeding Silk Touch), "Classy Glassy" and "Translucence" are in logic with no
  furnace and no glass structure. 45 crafted items carry a silk_mining source, including every
  glass, stained glass and pane.
  Where glass really generates (structures.json palettes):
  - glass: Ancient City, Woodland Mansion
  - glass_pane: Ancient City, Woodland Mansion, all four villages
  - white / yellow / orange stained glass panes: Plains and Savanna villages
  - magenta stained glass: End City; brown stained glass: Trail Ruins
  - black / light gray / white stained glass: Trial Chambers, only in 17-25% of them
  Fix: the silk route of a crafted block takes the structures that place it (`palette_structure`)
  instead of `_natural_origin`. Village panes / mansion glass stay strict; Trial Chambers stained
  glass goes glitch through the existing A16 rule.

- [ ] **9. The Silk Touch glitch route still includes piglin bartering**

  Where: `can_silk_touch`, glitch-only random-book branch uses `acquire("minecraft:enchanted_book")`.
  Bartering only ever gives Soul Speed books. Only affects yellow tiles.
  Fix: leave bartering out of that branch (chest and fishing books only).

---

## M. Mod suggestion: `/aem structures` pending list

- [ ] **M1. Separate "waiting for a player to load the chunk" from "being written"**

  Structures that need a player to load their chunk before they can be built are listed in
  `/aem structures` as "placement still being written". That reads as "generation is stuck".
  - List them in their own group (e.g. "waiting for chunk load: N").
  - Maybe show where they are (coords / nearest first). The list can get big if a lot was generated
    before the unlock, so cap it (nearest N, or a count per structure with an opt-in detail page).

---

## D. Docs and comments

- [ ] **10.** `docs/en_minecraft_aem.md` still says keep_inventory never keeps your XP; since D3 it
  keeps its share of XP points.
- [ ] **11.** `docs/setup_en.md` does not list `/aem deathlink mode random|all` (D5).
- [ ] **12.** The doc comment of `InventoryLockService.progressiveOrder` and the `inventory_lock`
  option text (options.py:559) end with "then the two remaining rows" without the order. Since F29
  it matters: middle row (slots 18-26) first, then the top row (9-17).
- [ ] **13.** The `MCWorld.get_filler_item_name` docstring says it draws "a filler buff"; it draws
  any item classified as filler, weighted by its count.

---

## Z. Final verification

- [ ] Generate.py on the playtest YAML (BACAP, structures + mobs locked), both starts, all three MC
  versions; one heavy job at a time.
- [ ] Logic compile time back to seconds (P1).
- [ ] Mod jar rebuild + in-game check of 1 (bosses tile) and M1.
