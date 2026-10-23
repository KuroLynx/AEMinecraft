# Improvement plan — player reports (docs/todo/improvement.txt)

Investigated 2026-10-22 on `release/v0.7.0` (a2f0a5f). Every logic item below was reproduced (or
ruled out) with `tools/why_rule.py`. It prints a rule's cheapest requirement sets, strict and glitch,
under the heaviest lock config, and `--without X` drops one item from the full pool to check that a
gate is really required.

The reporters were on **26.3**: abandoned camps only exist there. Every **Check** runs on all three
versions: `--mc 26.1.2`, `--mc 26.2` and `--mc 26.3`.

How to use this file: one branch per section (or per tightly-related group). Tick an item only once
its **Check** passes. When everything is ticked, run the final section.

```
python tools/why_rule.py [--mc 26.3] [--without "<item>"] "<advancement name prefix>" ...
```

---

> **Resume here (2026-10-22, evening):** A1–A11 done and merged into `release/v0.7.0` (unpushed).
> A11, A11b, A12, A12b and A13 done too: section A is complete. A8–A12 only ran their own **Check** lines: their
> regression + Generate runs are deferred to one pass at the end of section A (see Z).
>
> Found while checking A7 (fixed, `fix/glitch-cycle-depth`, merged): the "stalled" check was a
> segfault. Glitch prices on a dependency cycle nested every fixed-point round inside the next (824
> levels on BACAP), and `why_rule.py` evaluated them unmemoised. Cycle members are now named price
> refs (`{"k":"price"}`, export `prices` table, solved as a least fixed point in Java and Python):
> depth 22, BACAP export 5.5 MB → 1.7 MB. **Needs a mod jar rebuild** (an old jar can't read
> `price` nodes). The same work made `creaking_heart` a natural self-mined block (the resin cycle had
> no grounding; 26.3 strict resin no longer needs a Mansion).
>
> Tool notes: `why_rule.py` now reads its knock-out verdict from the export, like the mod
> (region-aware; glitch = the mod's yellow). `--routes 0` skips route expansion. It only matches
> names starting with "Advancement: ". Run heavy jobs one at a time.

## A. Logic leaks (apworld)

### A1. Status effects default to "own a brewing stand" — biggest root cause
`criteria.py::_price_effect` falls back to `_price_brew_stand()` for every effect that is not one of
the 3 in `tables.py::_EFFECT_SOURCE` (levitation, dolphins_grace, conduit_power). It never asks for
the potion's reagent, or for the effect's real source.
- BACAP *Hero of the Village* is `effects_changed: hero_of_the_village`. Today: Brewing + a village,
  with no raid mobs at all.
- *Marine Marauder* is water breathing. Today: Brewing + a village, with no pufferfish, turtle or
  conduit.
- The same goes for every other effect advancement (How Did We Get Here?, A Much More Doable
  Challenge, …).

Fix: give each effect its real sources.
- Potion effects: OR over the potion types that carry the effect, priced with `_price_potion`
  (reagent routes from brewing.json).
- Non-brewing sources, added to `_EFFECT_SOURCE`:
  - hero_of_the_village → `can_win_raid`
  - raid_omen → `can_raid`
  - bad_omen → kill a captain / ominous bottle
  - trial_omen → ominous bottle + Trial Chambers
  - water_breathing → turtle helmet / conduit
  - mining_fatigue → Elder Guardian
  - darkness → Warden / shrieker
  - wither → wither skeleton / wither rose
  - poison / hunger / … → their mobs and foods
- Keep `_price_brew_stand` only as the last resort, and make the audit below list every effect that
  still lands on it.

- [x] A1 fixed (fix/effect-sources, 2026-10-22: knock-outs + audit pass on 26.1.2/26.2/26.3; selfcheck, reachability, all-options, full Generate x3 PASS)
  - **Check:** `why_rule.py "Hero of the Village"`: every strict route contains Pillager, Vindicator,
    Evoker, Witch and Ravager, plus Villager and a village. Same on `--mc 26.3`.
  - **Check:** `why_rule.py "Marine Marauder"`: every route needs Pufferfish (or Fishing), Turtle
    (helmet) or a conduit, and none is "Brewing + village" alone. A guardian's rare pufferfish drop
    may appear as a **glitch** route only, which is the yellow the reporter expected.
  - **Check:** a throwaway loop over every effect named in all manifests prints which ones still fall
    back to brew-stand-only. Each one left is justified, or fixed.

### A2. Raids: logic must mirror the mod's raid gate
The mod refuses Bad Omen → Raid Omen while any of the 5 raid mobs is locked
(`BadOmenMobEffectMixin`). `can_raid` / `can_win_raid` already ask for that, and vanilla
`hero_of_the_village` uses it. The BACAP raid advancements don't:
- Hero of the Village (effect): fixed by A1.
- *War is Coming* (raid_omen effect): fixed by A1.
- *We're being attacked!* (`minecraft:voluntary_exile` trigger = a raid starting) → `can_raid`.
- *I've got a bad feeling about this* (kill an `is_captain` raider): captains are pillagers (outpost
  or patrol) or a raid's wave leader. The Witch route (`@Overworld + Entity Unlock: Witch`) is a leak.

- [x] A2 fixed (fix/raid-logic, 2026-10-22: knock-outs pass on 26.1.2/26.2/26.3, vanilla + BACAP Voluntary Exile; selfcheck, reachability, all-options, full Generate x3 PASS)
  - **Check:** `--without "Entity Unlock: Ravager"` → strict=False and glitch=False for "Hero of the
    Village", "War is Coming" and "We're being attacked".
  - **Check:** the strict routes for "Voluntary Exile" no longer include a Witch-only route.

### A3. Lingering potions: a 14% dispenser counted as green
Trial Chamber **dispensers** (`dispensers/trial_chambers/chamber`) hold lingering potions at 14%,
below the 30% limit. They stayed green for two reasons. Brewing (splash = potion + gunpowder,
lingering = splash + dragon's breath) wasn't a source at all, and `_demote` kept low-chance routes
whenever the dependable one left the start dimension.

Fix (decided 2026-10-22):
- `_demote`: any dependable route wins alone. With none, only the highest-odds low-chance route(s)
  stay green. The start-dimension exception is deleted.
- Brewing steps live in `brewing.json` `containers`. They are dumped from 26.3's brewing recipes
  (`PackDump.brewing`) and written by hand for 26.1.2/26.2. A water bottle counts as a `potion`.

- [x] A3 fixed (fix/potion-brewing-sources b5e83b9, 2026-10-22: without the End, Noxious Fumes and
  Gas! are strict=False, glitch=True on 26.1.2/26.2/26.3; selfcheck, reachability, all-options, full
  Generate x3 incl. Nether starts PASS; mod compiles on all 3)
  - **Check:** `why_rule.py --without "Dimension Unlock: The End" "Noxious Fumes" "Gas!"` →
    strict=False, glitch=True.
  - **Check (in-game, later):** re-run `/aem dump brewing` on 26.3 and diff it against the committed
    `brewing.json` (the `containers` section was derived from the jar by script).

### A4. The tipped arrow recipe forgets its potion
`tipped_arrow` has `recipes: [{ingredients: [arrow], station: crafting_imbue}]`, without the
lingering potion. *Imbued Projectiles* (`recipe_crafted tipped_arrow`) therefore costs only an arrow.
That is why it was oddly yellow: glitch routes like "Parrot" alone.

Fix: the imbue recipe takes `lingering_potion` + `arrow` (in the dumper, or in the recipe node for
`crafting_imbue`).

- [x] A4 fixed (fix/tipped-arrow-recipe, 2026-10-22: dump reads imbue `source`; 3 packs patched; checks pass on 26.1.2/26.2/26.3; selfcheck, reachability, all-options, full Generate x3, compile x3 PASS; confirm with an in-game re-dump later)
  - **Check:** `why_rule.py "Imbued Projectiles"`: every route carries a lingering-potion source
    (Trial Chambers for the dispensers, or the End for dragon's breath). No route like "Parrot" or
    "Villager + Trust" alone is left.
  - **Check:** `why_rule.py --without "Structure Unlock: Trial Chambers" --without "Dimension Unlock:
    The End" "Imbued Projectiles"` → strict=False and glitch=False.

### A5. Bees: honey and honeycomb don't need bees
- `honeycomb` has `gameplay: beehive` (shear a full hive), priced without the Bee.
- *Bee Our Guest* (smoke + bottle a **full** hive) has strict routes with no Bee: shears + pickaxe,
  or a stronghold.
- Not a bug: *Winnie the Pooh* and *Sticky Situation* come from honey bottles in Trial Chamber
  spawner rewards (`honey_bottle: structures trial_chambers`). That is a real bee-less route.

Fix: the `beehive` gameplay source and the "full hive" block predicate both require
`entity(Bee)`.

- [x] A5 fixed (fix/bee-gates, 2026-10-22: hive harvest needs Bee; smokey = campfire + Bee; checks pass on 26.1.2/26.2/26.3; selfcheck, reachability, all-options, full Generate x3 PASS)
  - **Check:** `why_rule.py --without "Entity Unlock: Bee" "Bee Our Guest"` → strict=False and
    glitch=False.
  - **Check:** "Winnie the Pooh" still shows its Trial Chambers route (unchanged on purpose).

### A6. ~~Coral blocks drop themselves without Silk Touch~~ NOT A BUG (checked 2026-10-22)
*Just a sample* has one requirement group that accepts **any** of 30 coral items, **dead coral blocks
included**. Mining a coral block with a plain pickaxe drops a dead coral block, so "Pickaxe Handling"
alone is correct. Living coral is correctly Silk-Touch-only: `acquire(tube_coral)` needs Silk Touch.

The silk audit found no leak either. The acquisition dump files entry-level Silk Touch drops
(`alternatives` children, 47 blocks on 26.1.2/26.2) under `mining`, not `silk_mining`. But the logic
gates mining through `block_mining.json`'s per-item `drops` tags (`_drop_tool_node`), and every
genuinely Silk-only drop is tagged `silk` on 26.1.2, 26.2 and 26.3. On 26.3 the condition is the
named predicate `minecraft:tool/can_silk_touch`, which the in-game dump reads.

- [x] A6 closed as not-a-bug. Reply in B8.

### A7. The Happy Ghast is reachable without the Nether
*Lead Zeppelin* has the strict route `@Overworld + Biome Finder + Entity Unlock: Happy Ghast`. A
happy ghast only exists by growing a dried ghast in water. A dried ghast comes from Nether fossils,
piglin bartering, or crafting it from ghast tears + soul sand: all Nether.

Fix: the happy ghast's spawn route is `acquire(dried_ghast)` + water + its unlock. It has no natural
spawn and no biome. Check whether `entities.json` gives it one.

- [x] A7 fixed (fix/happy-ghast-origin bd47fc0, merged 3c024df, 2026-10-22: Overworld start without
  the Nether → all strict=False glitch=False on 26.1.2/26.2/26.3; Nether start without the Overworld,
  every advancement × 3 versions → Hot Air Balloon, Lead Zeppelin, Pillow Fight, Snow Cone?, Stay
  Hydrated! all out (the stall was the glitch-depth bug above); selfcheck, reachability, all-options,
  full Generate x3 both starts PASS)
  - **Check:** `why_rule.py --without "Dimension Unlock: Nether"` on "Lead Zeppelin", "Hot Air
    Balloon", "Follow the Leader", "Test Flight", "Who Needs Elytra", "Big Blimp", "Pillow Fight" and
    "Snow Cone" → all false (or reached through soul sand from an Ancient City, if that counts).

### A8. Mob drops and loot of non-progression unlocks fall to yellow only
**Root cause found (2026-10-22):** `acquisition.py` marks a drop/loot route flimsy when the unlock
isn't FULL `progression` (`name not in self.progression_mobs` / `progression_structures`, 4 call
sites). But every Entity/Structure Unlock is at least `progression_skip_balancing`, which IS
progression for fill (it's only excluded from balancing). Fix: delete those 4 conditions plus the
two sets, and let odds alone decide (30% rule). The classification two-pass below is not needed.

`acquisition.py` (drops loop) marks a drop *flimsy* when `name not in self.progression_mobs`. The
same goes for structure loot and `progression_structures`. Unlocks are classified per seed, so a cow
or spider that nothing else "needs" makes its leather or string glitch-only. That is why *Help! I've
been framed!* (leather) and *A Masterpiece!* (string → wool) show yellow even with donkeys or spiders
unlocked. The strict graph lists no Cow, Donkey or Spider route at all.

Fix: classify by need. An Entity/Structure Unlock that gives a **reliable** (≥ `_GLITCH_CHANCE`)
source for an item some location needs becomes progression. That is a two-pass derive: build rules
treating every unlock as progression, then mark the unlocks those rules use.

- [x] A8 fixed (fix/flimsy-unlock-drops, merged afffc1d, 2026-10-22: the 4 conditions and both sets
  deleted, odds alone decide; checks pass on 26.1.2/26.2/26.3. Classification is unchanged, so the
  third check doesn't apply. Regression + Generate deferred to Z)
  - **Check:** `why_rule.py "Help! I've been framed"`: a strict route `@Overworld + Entity Unlock:
    Cow` (or Donkey, Horse, Llama) exists.
  - **Check:** `why_rule.py "A Masterpiece"`: a strict route with `Entity Unlock: Spider` exists.
  - **Check:** the item-pool classification counts are printed before/after (progression count goes
    up), and BACAP Generate still passes (see Z).

### A9. Trick or Treat accepts any mob
`trick_or_treat` = kill an entity wearing a carved pumpkin. Routes like `Cow + Pillager Outpost` or
`Pufferfish + Mansion` show the equipment predicate is priced as "carved pumpkin + any mob". Only
mobs with a head slot that a dispenser can equip (zombies, skeletons, piglins and friends) can wear
one.

Fix: derive a `can_wear_head` set in `EntitiesDump` (don't curate it) and use it for the subject of
any `equipment.head` predicate. The route also needs a dispenser (or the mob's own pickup).

- [x] A9 fixed (fix/trick-or-treat + fix/wear-head-dump, merged bb3c6bb, 2026-10-22: EntitiesDump
  derives `can_wear_head` = canEquipWithDispenser(carved pumpkin) over 32 spawned samples at Hard
  (zombies/skeletons only roll canPickUpLoot in finalizeSpawn); re-dumped in-game on all 3 versions →
  13 wearers (zombie + skeleton families, piglin, piglin brute, villager); the route also needs a
  dispenser; 80 routes per version, every one through a wearer. Regression + Generate deferred to Z)
  - **Check:** `why_rule.py "Trick or Treat"`: every route's mob is a head-wearing mob, and no Cow,
    Pufferfish or Camel Husk.

### A10. Lighten Up is too strict: oxidation and wax scraping are missing
*Bulbous!* is legit: Trial Chambers have `waxed_copper_bulb`. *Lighten Up* needs a lit
exposed/weathered/oxidized bulb plus an axe, and its only routes are crafted bulbs (blaze rod →
Nether). Logic is missing two transforms: an axe scrapes wax off (waxed → plain), and plain copper
ages (plain → exposed → weathered → oxidized) by waiting.

Fix: add weathering and scraping edges from the game's `WeatheringCopper` / `HoneycombItem` maps
(dump them), with the axe gate on scraping.

- [x] A10 fixed (fix/lighten-up, merged 0bb16a1, 2026-10-22: scraping derived from the waxing recipes
  (X + honeycomb → waxed_X, reversed with an axe), and a craftable aged block now also comes from
  its plain form + waiting; no new dump needed. Without the Nether: strict=True via Trial Chambers +
  Axe + Pickaxe on 26.1.2/26.2/26.3. Lighting takes a lever, already covered. Regression + Generate
  deferred to Z)
  - **Check:** `why_rule.py --without "Dimension Unlock: Nether" "Lighten Up"` → strict=True via
    Trial Chambers + Axe Handling (+ redstone).

### A11. Sponge Miner needs the Elder Guardian unlock
Every route asks for `Entity Unlock: Elder Guardian`. Wet sponge rooms are part of the monument.
Mining them needs the monument, a pickaxe (or nothing) and some way to breathe. It doesn't need the
elder guardians, which only make it harder (mining fatigue). The gate presumably comes from the
monument's boss gate (`_STRUCTURE_BOSS_GATES`) being applied to *placed blocks*, not only to the
boss itself.

- [x] A11 fixed (fix/sponge-miner, 2026-10-22). The cause is NOT the boss gate: Sponge Miner
  wants 64 sponges (bulk mode), which drops finite sources (the monument's wet sponge blocks) when a
  renewable one exists, and the elder guardian's drop counted as renewable. Elder guardians and
  piglin brutes are placed once by their structure and never respawn, so their drops now run dry
  (`_GENERATED_ONCE_MOBS`; not derivable: Java structure pieces place them, not datapack data).
  Also: a wet sponge placed in the Nether dries on the spot (new source, no furnace), and the new
  A10/A11 routes now keep their ingredients' dry/loose marks (`_route`), so a stack still prefers an
  honest route over them. Without the unlock: strict=True via the monument (Nether drying, or a
  furnace) on 26.1.2/26.2/26.3; "Kill Boss: Elder Guardian" still needs it on all three.
  Regression + Generate deferred to Z.
  - **Check:** `why_rule.py --without "Entity Unlock: Elder Guardian" "Sponge Miner"` → strict=True.
  - **Check:** "Boss Kill: Elder Guardian" still needs the unlock.

### A12. 26.3: structure-location advancements are free
On 26.3, *Atlantis?*, *Ahoy!* and *Boatception* are `@Overworld` only. On 26.1.2 they correctly need
the Ocean Ruin / Shipwreck unlocks. The structure ids are the same in both `structures.json`, so the
26.3 path through `criteria.py::_location` (`structures` predicate → `need("structure_id")`) loses
them. Find out why: content lookup, overlay merge, or id normalisation. Then audit every
`location.structures` advancement on 26.3.

Boatception's glitch route `Nether + Fishing + Bastion` is a separate oddity in the same predicate
(`vehicle.location.structures`). Fix it here too.

- [x] A12 fixed for list-form predicates (fix/structure-location-26-3, 2026-10-22). The cause: the
  26.3 BACAP pack writes `location.structures` as a LIST, and `_location` read only a string, so a
  list pinned nothing. Now an OR of its structures. Atlantis? and Boatception need their unlocks on
  26.1.2/26.2/26.3. Ahoy! is not done: it uses a structure TAG, see A12b.
  - **Check:** `why_rule.py --mc 26.3 "Atlantis" "Ahoy" "Boatception"`: every route has the matching
    Structure Unlock. The same on 26.1.2 and 26.2.
  - **Check:** a throwaway loop over every manifest advancement with `location.structures` compares
    26.1.2 against 26.3 and finds no route that drops its structure unlock.

### A12b. Structure tags in location predicates are unpriced
The 26.3 BACAP pack also writes structure TAGS: `#minecraft:shipwreck` (Ahoy!, Titanic, Raidin'
Master), `#minecraft:ruined_portal` (Ancient Restoration, From Whence It Came!, Now You're Thinking
With Portals, Raidin' Master), `#minecraft:abandoned_camp` (Raidin' Master). `_location` only knows
village tags, so any other tag reads UNKNOWN and the advancement falls back to its parent's rule.
Ahoy! currently routes through Atlantis?'s ocean ruins.

Fix: dump `worldgen/structure` tags into `tags.json` (PackDump, `structure` key; needs an in-game
re-dump on all 3 versions) and resolve a tag to the OR of its members in `_location`.

- [x] A12b fixed (2026-10-23, with A11b: PackDump writes `biome` + `structure` tags, re-dumped in-world
  for vanilla + BACAP on all 3 versions; `_location` resolves both. Ahoy!/From Whence It Came!/Void
  Walker gated correctly on 26.1.2/26.2/26.3)
  - **Check:** `why_rule.py --mc 26.3 "Ahoy" "Titanic" "From Whence It Came"`: every route has the
    tag's Structure Unlock (Shipwreck / Shipwreck Beached, a Ruined Portal variant).

### A11b. The compiler fails loudly on input it can't read (decided 2026-10-22)
A12 and A12b are the same class of bug: a predicate field in a shape the compiler doesn't handle (a
list where it expected a string, a tag where it expected an id) was silently skipped, and the
advancement became free or fell back to its parent. Every reader in `criteria.py` should raise on a
shape it doesn't understand, instead of returning None. A new pack format then breaks generation
loudly in the audit, not quietly in a player's seed.

- [x] A11b done (feat/compiler-fail-loudly, 2026-10-23: readers raise CompileError on a present field
  of an unreadable shape; unknown KEYS stay audited. Found + fixed: biome lists (read as Overworld),
  location_check, biome/structure tags; entity_scores declared as pinning nothing.
  `tools/check_compile_all.py` PASS on all 3 versions. Also fixed the dump button: it dumped pack
  files on the title screen, where block_mining crashed ("Components not bound yet") and nothing
  had been rewritten since 2026-09-30; it now dumps everything in its throwaway world)
  - **Check:** `tools/audit_predicate_coverage.py` + a compile of every manifest (vanilla + BACAP
    packs) on 26.1.2/26.2/26.3 raises nothing; a deliberately malformed predicate (a list for a
    string field) raises with the advancement and field path in the message.

### A13. Group biome variants of a structure into one unlock (decided 2026-10-22)
26.3 has one Abandoned Camp per biome (18 items), and every version splits villages (5), ruined
portals (7), ocean ruins, shipwrecks, mineshafts and so on.

New option **`split_structure_variants`** (`split_structure_variants: 'true' / 'false'`, default **`false`**), a
`Toggle` in `options.py` + `template.yaml`:
- `false` (default): structures that differ only by biome share **one** unlock item, e.g.
  `Structure Unlock: Village` (plains/desert/savanna/snowy/taiga), `Structure Unlock: Ruined Portal`
  (all 7), `Structure Unlock: Abandoned Camp` (all 18), `Ocean Ruin` (cold/warm),
  `Shipwreck` (+ beached), `Mineshaft` (+ mesa). One item unlocks every variant.
- `true`: today's behaviour, one item per structure.
- **Grouping is derived, not curated**: a group is structures whose ids share a base and differ by a
  biome suffix. Add a `group` field to `structures.json` (or derive it in the overlay merge), so
  content packs can join a group too. List the derived groups for 26.1.2/26.2/26.3 in the PR and
  review them by hand once.
- Logic: `structure(name)` asks for the group's item when grouping is on. Location/advancement names
  stay per structure. The mod's structure registry maps every member id to the group item (slot data
  carries the mapping, so the mod needs no option of its own).

- [x] A13 fixed (feat/structure-variant-groups, 2026-10-23). Groups come from the game's structure tags
  (a tag whose members are `<tag>` / `<tag>_<variant>`): Village 5, Ruined Portal 7, Ocean Ruin 2,
  Shipwreck 2, Mineshaft 2, Abandoned Camp 18 (26.3); identical on 26.1.2/26.2 apart from the camps.
  Ruined Portal / Shipwreck / Mineshaft reuse their structure's item; Village / Ocean Ruin / Abandoned
  Camp are new ids (0xEC0300 + lowest member id). Pool 36 -> 23 (26.1.2), 54 -> 24 (26.3); `true`
  restores them. The mod needed no change (it already unlocks every structure mapped to an item id).
  Such a poser: routes via the grouped camp; `--without` it stays reachable through the Copper Golem
  route (legit), and is out without both. all-options + Generate with both values: in the Z pass.
  - **Check:** `split_structure_variants: false` on 26.3: AP item names contain exactly one
    `Structure Unlock: Abandoned Camp`, one `Village`, one `Ruined Portal`. `true` → the per-biome
    items as today.
  - **Check:** `why_rule.py --mc 26.3 "Such a poser"` routes through `Structure Unlock: Abandoned
    Camp` (grouped) and `--without` it → strict=False.
  - **Check:** pool size: the item count drops by the grouped members, and `verify_all_options.py`
    + full Generate pass with both values on all 3 versions.
  - **Check (in-game):** receiving the grouped item places camps in two different biomes.
- [x] A13 follow-up (feat/structure-group-tracker, 2026-10-23): option renamed `structure_variant` ->
  `split_structure_variants` (same meaning, reads the way it behaves). Grouped, the Structure Unlocks
  tab shows one `aem:unlock_structure/group/<name>` tile per group instead of its members, and
  `TrackerLayoutService` re-grids the tab's active tiles into full rows of 8.
  - **Check (in-game):** grouped village unlock places villages — confirmed 2026-10-23. Re-grid not
    seen yet.

### A14. Archaeology loot forgot the brush (found checking B2, 2026-10-23)
`player_generates_container_loot` on an `archaeology/<structure>` table was priced as just the
structure. Vanilla *Respecting the Remnants* (BACAP rewrites it to "hold a sherd") was in logic with a
Desert Well / Trail Ruins unlock and no Brush.
- [x] A14 fixed (fix/archaeology-brush e5fced3): `_price_loot_table` adds `has_brush()` for
  archaeology tables. Vanilla routes now all carry Brush Handling + copper + a feather; BACAP
  unchanged. selfcheck, reachability, all-options, compile x3, vanilla Generate x3 PASS.

### A15. Honey bottles had no bee route (found checking B3, 2026-10-23)
Bottling a full hive is hard-coded (BeehiveBlock), so the dump never saw it. Honey bottles' only
source was the Trial Chamber vault (3/25 per common roll, ~24% per vault), which `_demote` promoted to
green because nothing else existed.
- [x] A15 fixed (fix/b-section-leaks e401894): `beehive_bottle` harvest = glass bottle + Bee + a hive.
  Winnie the Pooh / Sticky Situation strict now need the Bee; the vault is glitch.

### A16. Structure palettes had no chances (found checking B4, 2026-10-23)
`structures.json` listed every block any template can place. A copper golem statue is in 9 of 297
camp templates (2-14% of camps), yet Such a poser was green with the camp unlock alone.
- [x] A16 fixed (fix/b-section-leaks): `JigsawChances` (dump) reads a jigsaw structure's pools and
  writes `block_chances`; a palette block under 30% is a glitch-only route (`palette_structure`). Packs
  patched from the jars (scratch mirror of the Java). Non-jigsaw structures (shipwreck, ruined portal,
  igloo basement...) still read 100%.
  - **Check (in-game):** `/aem dump structures` on 26.1.2/26.2/26.3 reproduces the committed files.

### A17. Dead coral counted as generating (found checking B8, 2026-10-23)
Dead coral blocks had no origin, so they read as lying around the Overworld: BACAP Just a sample was
green with a pickaxe and no Biome Finder. Silk Touch mining also skipped the biome gate (coral
plants/fans), and the dump listed only coral blocks for the coral features.
- [x] A17 fixed (fix/b-section-leaks 3d43d01): dead coral is aged living coral; silk mining asks the
  block's origin; plants/fans/wall fans added to the dump and the 3 block_biomes.json.

---

## B. Reported but not bugs — reply to the players

- [x] B1 (verified 2026-10-23: Cod/Salmon unlock only) *Direct Fishing* (kill a cod/salmon/…): bare hands work, so no Sword or Fishing is needed.
  It needs the fish unlock only. (Verified: `--without "Knowledge: Sword Handling"` → still true,
  correctly.)
- [x] B2 (verified 2026-10-23; BACAP's Remnants is "hold a sherd", so Trial Chambers vaults count. The vanilla one was a leak: A14) *Brushed Away* vs *Respecting the Remnants*: sherds drop from Trial Chamber pots/vaults
  without a brush, while a brush needs Brush Handling + copper (Material Handling ×2) + a feather.
  Villages do count as a feather source. Out of logic means one of the first two was missing.
- [x] B3 (2026-10-23: half right; vault honey is ~24%/vault, and there was no bee route -> A15) *Winnie the Pooh* / *Sticky Situation*: Trial Chamber spawners reward honey bottles, so no
  bees are needed.
- [x] B4 (2026-10-23: the reporter was right; statue in 2-14% of camps -> A16) *Such a poser* without Copper Golem: on 26.3, abandoned camps generate copper golem statues.
  Confirm the statue is in the camp palette (`packs/minecraft_26_3/structures.json`). If it isn't,
  it becomes a leak in A13.
- [x] B5 (verified 2026-10-23 on Cherry Bomb: strict needs Biome Finder, glitch doesn't) "Craft every X wood" yellow vs green: rare-biome woods (jungle, dark oak, mangrove, cherry,
  pale oak, acacia, …) need the Biome Finder in strict logic. Glitch waives
  the Finder, so they show yellow. Verify on one: `why_rule.py "<cherry wood advancement>"`.
- [x] B6 (2026-10-23: strict routes are Turtle + Armor Handling, or Buried Treasure + Fishing) Marine Marauder: becomes yellow-only through guardians once A1 lands.
- [x] B8 (2026-10-23: BACAP-only; dead coral drop is right, but logic skipped the Warm Ocean -> A17) *Just a sample* with only a pickaxe: the advancement accepts dead coral too, and a coral
  block mined without Silk Touch drops a dead coral block. See A6.
- [x] B7 (verified 2026-10-23: strict needs the Ender Dragon unlock + End) *Noxious Fumes* / *Gas!*: the reporter was right. They now need the End (brewing with
  dragon's breath) for green, and Trial Chamber dispensers (14%) only make them yellow. See A3.

---

## C. New logic options (decided 2026-10-22)

- [x] C1 done (feat/exploration-logic, 2026-10-23). `combat_ready()` (strict only) = Sword/Axe Handling +
  Material Handling iron + `has_food_supply()` + slot group `armor_pair`. It sits on `can_raid` (so
  `can_win_raid`, raid captains in a raid and raid gifts), `can_defeat` for every BOSS-category mob, the
  Trial Omen effect, the Wind Burst book and the ominous-only Trial Chambers items (heavy core, ominous
  trial key: Revaulting, Core Keeper). Under Lock and Key is the normal vault and stays ungated.
  **Deviation:** the mod opens armor slots boots first, so the 2 slots are boots + leggings, not
  helmet + chestplate. Off -> routes as before. selfcheck, reachability, all-options, compile x3, BACAP
  Generate x6 + seeds 111/2222 on 26.1.2/26.3 + an inventory_lock seed PASS.
- [ ] C1 **New option `combat_logic`** (decided 2026-10-22). A `Toggle` written
  `combat_logic: 'true' / 'false'`, the twin of `exploration_logic` (C2). Default `true` (`false` keeps today's behaviour); add it to
  `template.yaml`. The reporter believed it was already handled: raids, Trial Chambers and hard kills
  were in logic with copper tools, no weapon and no armor slots. Today logic asks for none of that.
  - `true`: a **hard fight** is in logic only once you have **a weapon**, **food**, and **armor
    you can wear**.
  - **Hard fight** (derived from the rule, not a list of names): any location whose rule goes through
    `can_raid` / `can_win_raid`, the Trial Chambers' ominous vault / Trial Omen, or the defeat of a
    boss (`can_defeat` of Wither, Warden, Elder Guardian, Ender Dragon, plus any boss in `boss_list`).
    The gate goes on those helpers themselves. Every location that reaches them inherits it, including
    item routes like raid gifts, and nothing else changes.
  - **Weapon:** Sword Handling or Axe Handling, plus Progressive Material Handling ≥ the iron tier.
  - **Food:** the same `can_get_food()` helper as C2.
  - **Armor:** when `inventory_lock` locks armor slots, ≥2 armor slots (helmet + chestplate). Otherwise
    this part folds away.
  - Strict graph only. The glitch graph ignores it, so these read yellow instead of red.
  - **Check:** `why_rule.py --opt combat_logic=1 "Hero of the Village" "Under Lock and Key"` plus a
    Wither and a Warden kill: every strict route has Sword/Axe Handling + the iron Material tier + the
    food gate. `false` → output identical to before.
  - **Check:** `verify_all_options.py` with both C1 and C2 on passes, on both starts.
  - **Check:** a BACAP `Generate.py` with both on passes on 26.1.2 and 26.3. The gate moves progression
    earlier, so watch for fill failures.
- [x] C2 done (feat/exploration-logic, 2026-10-23). The food helper is `has_food_supply()` (`can_get_food()`
  already meant "anything edible once", for the Husbandry root) and also takes the Hoglin, so a Nether
  start's Nether trips don't wait for the Overworld. "Exploration" = every criterion is
  `changed_dimension` / `nether_travel`, or a `location` whose location names only a biome / structure
  / dimension / position (standing on a block, light, an empty location: no). 12 vanilla and 62 BACAP
  advancements. selfcheck, reachability, all-options, compile x3, BACAP Generate x6 (3 versions x both
  starts) and one with every inventory slot locked PASS.
- [ ] C2 **New option `exploration_logic`** (decided 2026-10-22). It's a `Toggle` in `options.py`,
  written `exploration_logic: 'true' / 'false'` in the YAML like `glitch_logic`. Default `true`
  (`false` keeps today's behaviour). Add it to `template.yaml` too.
  - `true`: an exploration advancement is in logic only once you have **one reliable food source**
    **and** **≥1 inventory slot**.
  - **Exploration advancement** (derived, not curated): every criterion is a `location`-type visit
    (biome / structure / dimension / position, e.g. Atlantis?, Ahoy!, Adventuring Time). Advancements
    that also need an item or kill are not included. They already need the Structure Finder / Biome
    Finder in strict logic. That stays, and this gate is added on top of it.
  - **Reliable food:** Hoe Handling (crops), or one passive food-mob unlock (cow, pig, chicken, sheep,
    rabbit, mooshroom, cod, salmon). Built as a `can_get_food()` helper, shared with C1.
  - **Inventory slot:** only when `inventory_lock` is enabled; otherwise the clause folds away.
  - Strict graph only. The glitch graph (yellow) ignores it, so a tile still reads yellow instead of
    red.
  - **Check:** `why_rule.py --opt exploration_logic=1 "Atlantis"`: every strict route keeps its
    Structure Finder / Structure Unlock and adds the food gate (and an `Inventory Slot` once `inventory_lock` is on). `false` → routes identical to
    before (diff the output).
  - **Check:** `verify_all_options.py` with the option on passes (no dead lock on a Nether start).

---

## D. Mod (Java)

### D1. Raid feedback
The Bad Omen hold is silent. When `BadOmenMobEffectMixin` holds a raid, tell the player once per Bad
Omen (throttled): "A raid can't start until these are unlocked: Ravager, Evoker, …", naming the ones
still locked.
- [ ] D1 done. Built on `fix/raid-feedback`; in-game check pending. **Check (in-game):** drink an ominous bottle in a village with a raid mob locked → one
  chat line listing exactly the locked raid mobs, with no spam each tick.

### D2. Dispensers equip armor into locked armor slots
The inventory-lock mixins cover the ArmorSlot, `Equippable.swapWithEquipmentSlot`, the swap keys and
offhand. They don't cover `EquipmentDispenseItemBehavior.dispenseEquipment` → `setItemSlot` on a
player. Block it when the target is a player and the slot is locked (the item stays in the
dispenser / drops as normal).
- [x] D2 done. `canDispenserEquipIntoSlot`; checked in-game. **Check (in-game):** a locked armor slot plus a dispenser firing a helmet at you leaves
  the slot empty. Unlocked, it equips. `tools/check_mixin_targets.py` passes on all 3 versions.

### D3. XP still drops with keep inventory
`KeepInventoryService` deliberately leaves XP alone. Make XP follow the kept share: 100%
(active/progressive at max) keeps all XP like the gamerule does, and partial keeps the same share
(the rest drops).
- [x] D3 done. With F33; checked in-game. **Check (in-game):** `keep_inventory: active` → die with levels, respawn with the same
  levels, and no orbs dropped. Progressive at 50% → about half kept.

### D4. Mineshaft unlock lagged the server
"362 instance(s) queued for rebuild", then maxed CPU and `Trying to mark a block for post processing
… not supported` spam. The pre-connect hypothesis is already handled: until slot data arrives,
everything counts as locked (`StructureLockService.isStructureLocked` → `isAwaitingSlot`). Mineshafts
generated before connecting were suppressed and are rebuilt now, which is correct. The cost is the
rebuild itself:
- `StructurePlacementQueue.Rebuild.step` places chunks of each start. Mineshafts span many chunks,
  and placing into unloaded chunks forces chunk loads/generation on the server thread. The "at least
  one per tick" rule then ignores the 2 ms budget.
- Each piece's post-processing marks hit `ServerLevel` (unsupported, so a WARN per block). The
  logging alone is costly, and fences/rails are left un-updated.

Fix:
1. Only place chunks that are loaded. Defer the rest to a chunk-load hook. A far-away mineshaft is
   then rebuilt when someone gets there, never as a sync load.
2. Measure the budget against the real per-chunk cost.
3. Route post-processing marks to the chunk, or apply the shape update directly, so the WARN is gone.

- [ ] D4 done. Built on `fix/structure-rebuild-on-load` (wait for loaded chunks, covers F35); in-game check pending.
  - **Check (in-game, dedicated server):** pre-generate a large area before connecting, then
    `/send` Structure Unlock: Mineshaft. MSPT stays under 50 (use `/tick query`), and the log has no
    post-processing WARN.
  - **Check (in-game):** walking into a far mineshaft's chunk shows it built, with connected
    fences/rails.

### D5. DeathLink mode for multiplayer servers
Add a server-side setting: an incoming DeathLink kills **one random** living player (current
behaviour, `DeathLinkService.applyRemote`) or **all** of them. It's a `/aem deathlink` sub-command persisted like the other server preferences
(see `DeathLinkPreference`).
- [ ] D5 done. Built on `feat/deathlink-mode` (`/aem deathlink mode random|all`); in-game check pending. **Check (in-game, 2 clients):** in `random` mode a bounce kills exactly one player;
  in `all` mode it kills both. The setting survives a restart.

---

## E. "Will work on later" (quality of life, no rush)

- [ ] E1 **Why is this in logic?** Tooltip line on an advancement tile: the first satisfied route,
  humanised ("Trial Chambers + Pickaxe Handling"). The client already evaluates the rule
  (LogicEvaluation). Walk the satisfied OR branch and collect its Has/region leaves, the same idea as
  `tools/why_rule.py`. **Check:** the tooltip names the unlocks that make it green, and it matches
  `why_rule.py`.
- [ ] E2 **Hinted at a glance + into-logic alert.** Add a marker on hinted tiles and a count badge on
  tabs. Send a chat line when a hinted location turns green (client-side transition, once per
  location). **Check:** hint a location → marker + badge. Receive its unlock → one chat line.
- [ ] E3 **Biome Finder: cycle targets.** A "next" control on the pick screen (or re-pick on the
  map) moves to the next-nearest instance of the same biome, so the server search skips the previous
  targets. **Check:** with two Cherry Groves, "next" points at the second one.

- [ ] E4 **Tracker tabs as a custom panel (hybrid).** All 4 tracker tabs (Kills, Entity Unlocks,
  Structure Unlocks, Knowledge) keep their category root as a real advancement, so the tab buttons
  stay native and other mods still see ordinary tabs. When one of the `aem:category/*` tabs is
  selected, the mod draws its own grid in the content area from `slot_data["trackers"]`: section
  headers (dimension / mob category / progressive vs gates), a fixed order, rows wrapping to the
  window width, no gaps. Reuse the per-tile drawing, tooltip, in-logic glow, hold-to-hint, gray
  progress fill and X/N code. Under Better Advancements / Advancements Reloaded / Paginated
  Advancements, fall back to today's tree layout first and port each later if worth it. Once it's
  done, the tracker tile datapack (`tools/gen_tracker_advancements.py`) and `TrackerLayoutService`
  can shrink to just the roots. **Check (in-game, 26.1.2/26.2/26.3):** each tab draws a gapless grid
  with headers, tooltips + hints + glow still work, and the compat mods still show the tree.

---

## F. Player report (2026-10-23, verified against fix/b-section-leaks b0c1762)

A long-time player's list. Each item was checked before landing here: `why_rule.py` routes and
knock-outs on 26.1.2 (26.3 for the 26.2+/26.3-only advancements), BACAP on, everything locked, or a
read of the mod code. **Done** = already behaves as the report asks. **Open** = still wrong today.

### F-logic: advancements that read the wrong thing
- [x] F1 **Structure lists / families** (Raidin' Master, Atlantis?). Done: on 26.3 Raidin' Master is out
  without `Structure Unlock: Abandoned Camp`; Atlantis? needs `Ocean Ruin`.
- [x] F2 **Location conditions** (Quicksand, Ahoy!, Now You're Thinking With Portals). Done: Quicksand
  needs the Nether (or an Ancient City's soul sand), Ahoy! a Shipwreck, Portals a Ruined Portal.
- [x] F3 **Worn items, falling blocks** (Stink Bomb, Classic Cartoon Comedy). Done (26.3): Stink Bomb
  prices the TNT (Creeper / Ghast), Classic Cartoon Comedy needs the Anvil.
- [x] F4 **Item colours** (Colorful Cavalry). Done: the compiler reads `dyed_color` (a vanilla dye colour asks for that dye); strict is out `--without` Squid + Glow Squid + Wither. Was: still in logic `--without` Squid, Glow Squid and
  Wither, so the 16 dyes aren't asked for. Each colour must ask for its dye.
- [x] F5 **Suspicious stew effects** (I'm gonna be sick!). Done: `suspicious_stew_effects` item components ask for a stew of that effect; strict is out `--without` Biome Finder or Wither. Was: in logic `--without "Biome Finder"`
  (eyeblossom = Pale Garden) and `--without "Entity Unlock: Wither"` (wither rose). Each effect must
  ask for one of its flowers. Shares the flower table with F27.
- [x] F6 **Ominous banner** (Superhero of the Village). Done: a banner with more layers than a loom applies is a raid captain's kill; strict is out `--without` Pillager. Was: in logic `--without "Entity Unlock:
  Pillager"`; routes are Sword Handling or Spider (string -> wool). The ominous banner can't be
  crafted (8 patterns, the loom does 6): it needs a pillager (a patrol captain, no village or raid).
- [x] F7 **Trick or Treat!** Done (A9): needs a zombie/skeleton-family mob.
- [x] F8 **Such a poser** Done (A16): strict needs the Copper Golem; the camp statue is glitch.

### F-logic: routes that should not count
- [x] F9 **Mining takes the pickaxe itself.** Done: a tiered block asks for a pickaxe of exactly that tier (iron: Crafting Table + Furnace or iron loot/golem), every route kept; `why_rule` now runs with `knowledge_gates: All`. The extra routes made rule building 3x slower, so `fixed_point._minimal` files kept alternatives by their rarest leaf and `_and` skips a row an alternative already covers (same output, spoiler and logic export identical; BACAP 26.1.2 rule build 47 s -> 51 s with F9, 24 s without). Was: Seeing Red strict = Pickaxe Handling + Material
  Handling x3, no Furnace / iron. Mining must also acquire the cheapest pickaxe that works (an iron
  one needs iron, so a furnace or iron loot); the Nether's obsidian likewise a diamond pickaxe.
- [x] F10 **Silk Touch from random books.** Done: a named enchantment's book is a librarian (novice trade + book + lectern to reroll); a random book is glitch only, for Silk Touch and every named enchantment. Thin Ice! strict stays `--without` Knowledge: Enchanting (librarian) and is out without the Villager too. Was partly done: Thin Ice! strict now goes through Knowledge:
  Enchanting and the chest books are glitch only, but `can_silk_touch` still takes ANY
  `enchanted_book` + anvil. Make it a librarian (rerollable) for a specific enchantment; random
  chest/fishing books stay a glitch route. An advancement asking for any book is unchanged.
- [x] F10b **Enchanted item with no item named** (Mace Windu). Done: an enchantment asked for on an unnamed item also asks for an item it can go on (`supported_items`, dumped into tags.json `enchantment_items`, 3 vanilla packs patched from the jars). Mace Windu now needs the Breeze + Mace Handling.
- [x] F10c **Enchanting table only on its primary items.** Done: audited all 43 enchantments vs the jars (table / librarian `#tradeable` / the 3 loot-only sources all match on 26.1.2, 26.2, 26.3). The table route now needs a named item in the enchantment's `primary_items` (tags.json `enchantment_table_items`): Sharpness/Smite/Bane on an axe, Smite/Bane/Fire Aspect on a mace, Thorns off a chestplate need an anvil. No BACAP advancement changed (the Master ones also ask for Mending).
- [x] F11 **Fossils.** Done: an Overworld fossil's bone blocks (a feature nothing points at) are glitch only; crafting, the Nether Fossil and Trial Chambers stand. 5 strict rules changed (Boned, Paleontologist, Moss Maker, One-course meal, Combine Harvester: bone meal now comes from a skeleton-family mob or the Composter). Was: `bone_block` self-mines as a natural block (fossils), while a fossil is
  a few blocks buried per dozens of chunks. Boned is fine today (Skeleton or Composter), so check
  which other rules ride `bone_block`, then make the fossil route unreliable.
- [x] F12 **Aged copper blocks** (Grate Expectations, Don't Come a Copper!). Done: `_aged_source` tries `<plain>_block` (exposed_copper ages from copper_block); Grate Expectations needs a furnace or the Trial Chambers. Was: strict route
  Pickaxe Handling + Material Handling x1. `_aged_source("exposed_copper")` strips to `copper`, which
  isn't an item, so the block reads as natural. Full blocks age from `copper_block`.
- [x] F13 **Stripped logs** (Shelfception). Done: the free-wood shortcut no longer covers stripped logs/stems: log + an axe, or a village/camp palette (Shelfception without Axe Handling stays in only through a village). Was: strict = Knowledge: Shelf, no axe. `stripped_*_log`
  self-mines as natural. It needs a log + an axe, or a structure that places it.
- [x] F14 **Coral** Done (A17).
- [x] F15 **Happy ghast needs the Nether** Done (A7): Lead Zeppelin needs @Nether on every route.
- [x] F16 **Camel husk** (Kill Entity: Camel Husk). Done: parent-bound to the Husk (Husk.finalizeSpawn spawns it): its unlock + Husk + Biome Finder. Was: strict = its own unlock only. It only comes
  ridden by a husk in a desert: Husk unlock + desert (Biome Finder).
- [x] F17 **Wolf armor** (Armadoggo, Good as New, Shear Brilliance). Done: wolf armor used on / worn by / sheared off a wolf needs a tamed wolf; shears on a wolf also the wolf armor. All three out `--without` Wolf. Was: Armadoggo / Good as New ask
  Wolf + Armadillo but no taming (bones); Shear Brilliance asks Wolf + shears, no Armadillo and no
  armor. All three need a tamed wolf; Shear Brilliance also the wolf armor.

### F-logic: routes that were missing
- [ ] F18 **Nether portal from water + lava.** Open: entering the Nether needs obsidian (Ruined Portal /
  Village loot, or mining). A water bucket over lava in the frame's shape also builds a portal. The
  way back still needs obsidian (water evaporates in the Nether).
- [ ] F19 **Cobweb string with water.** Open: Fishy Business strict needs Sword Handling (or a Strider).
  Water washes a cobweb away and drops string: a water bucket is a route.
- [x] F20 **Copper bulbs** Done (A10): Lighten Up has the axe-scrape route from Trial Chambers.
- [ ] F21 **Naturally generated blocks with a recipe.** Partly done: Resinance needs the Biome Finder,
  not the camp. Still to check: terracotta, coarse dirt, moss carpet, nether wart block, smooth
  basalt (block_biomes says they generate; `_placed_block_origin` may still read them as crafted).
- [ ] F22 **Cured zombie villagers.** Open: What a Deal! needs `Structure Unlock: Village`. Curing a
  zombie villager (weakness potion + golden apple) is another villager, and trading shouldn't ask for
  a village on top of the villager (`can_sell_to_villager` ANDs `any_village()`).
- [x] F23 **Crafting criteria cost only the recipe.** Done: Boned strict is Skeleton alone (or
  Composter); no extra composter on the bone route.
- [ ] F24 **Armor slots one at a time.** To verify: with `inventory_lock` armor on, does each armor
  piece need its own slot (boots first), or does logic wait for all four? `why_rule --opt` only takes
  ints, so check with a YAML (Living Like Kings, golden boots).

### F-logic: unreliable routes
- [ ] F25 **Raid gifts.** Open in code: `*_gift` gameplay tables carry no chance, so they read as 1.0
  (reliable) behind `can_win_raid`. Make them as unreliable as mob drops, with a drop winning over
  them. (Cow Tipper / Salmonella Poisoning / Humble Bundle are fine today only because a drop exists.)
- [ ] F26 **Villager trades when villages aren't progression.** Open: `can_trade_villager` is always
  reliable. When the seed doesn't make villages progression, trades should be unreliable (leather
  gear, arrows, cooked food and Help! I've been framed! fall back to their mob routes).

### F-logic: status effects
- [x] F27 **Stew effects.** Done with F5: effect -> flowers is read off the stew recipes (the dump now records their `effects`; the 3 vanilla packs patched from the jars, `/aem dump acquisition` must reproduce them). Was: `tables.py` lists one flower per effect (weakness = white tulip, night
  vision = poppy, blindness = azure bluet without eyeblossom, nausea = pufferfish only, no stew). Use
  the full flower -> effect table (shared with F5).
- [ ] F28 **Beacon effects need a pyramid.** Open: haste / speed / strength / resistance /
  regeneration / jump boost ask `beacon` only. Add `can_get_beacon_base()` (it exists, used by
  construct_beacon). No visible change today (the beacon already costs the Wither + iron tools).

### F-mod
- [ ] F29 **Inventory rows unlock from the hotbar up.** Open: `InventoryLockService` opens the rest rows
  9-17 before 18-26 (hotbar, row 3, row 1, row 2). Should be hotbar, row 3, row 2, row 1.
- [ ] F30 **Inventory Shuffle only between unlocked slots.** Open: `TrapEffects.inventoryShuffle`
  permutes all 36, hiding items in locked slots.
- [x] F31 **Dispensers into locked armor slots** = D2 (done).
- [x] F32 **keep_inventory keeps XP** = D3 (done).
- [x] F33 **Totem with keep_inventory.** Done with D3 (hook moved to `dropAllDeathLoot`). Was: nothing in the mod checks for a totem, so kept items are
  set aside before the totem fires and the player dies. Skip when a held totem will save them.
- [ ] F34 **Owed structures after a restart.** Partly: `SERVER_STARTED` releases unlocked content only
  on an offline start. Verify an online restart with owed structures (and held mobs) places them
  without `/aem structures release`.
- [ ] F35 **Rebuilt far from the player.** Built with D4 (defer to loaded chunks). Was: the placement queue never loads the target area, and
  buried treasure (sea floor) / shipwrecks / ruins / igloos / ruined portals / pyramids / huts read an
  unloaded chunk's ground as the world bottom. Load the area first. Overlaps D4 (which defers to
  chunk load instead): pick one approach.
- [ ] F36 **Checks under a hidden advancement.** Open: only tracker tiles are re-parented
  (`TrackerLayoutService`). With BACAP + challenge_sanity frames, How Did We Get Here? sits under
  Global Vaccination (hidden) and shows nowhere. Re-parent any visible check onto its nearest visible
  ancestor.
- [ ] F37 **Goal tiles gray only on their own count.** Open: `DataLogicProvider` grays a goal tile once
  enough targets are *reached* (checked), so a release / collect / another client checks without
  earning ("Advancements" read done at 325/350). Gray only when the tile's own (earned) count is
  complete; enough checked but not enough earned is **blue** (COLLECTED), like a per-location tile
  that was released/collected. Same for the Bosses tile.
- [ ] F38 **Inventory buttons.** To verify in-game: with the Biome Finder the third button sticks out of
  the window, and the recipe book moves the window but not the buttons. Fix: a row along the top
  edge that follows the window.
- [ ] F39 **BACAP rewards marker in the world.** Open: `BacapConfigService` keeps its "already
  configured" marker through `APWorldPaths` (the `archipelago/` folder), which players copy to a new
  map, so the new world never gets bacap_rewards applied. Keep it in the world's own data.
- [ ] F40 **Replacement item for links / plando / start_inventory_from_pool.** Open: the world has no
  `get_filler_item_name`, so AP's default picks any item (a Knowledge, an unlock not in the pool, or
  one the seed's MC version lacks, which crashed: "Abandoned Camp"). Draw from the normal filler pool.

---

## Z. Final verification (run when everything above is ticked)

- [ ] Every **Check** line above re-run on the final branch, on `--mc 26.1.2`, `--mc 26.2` **and** `--mc 26.3`.
- [ ] Deferred from A8–A11 (each only ran its own Check): selfcheck, reachability, all-options and
      full Generate x3 × both starts, once, on `release/v0.7.0` after section A. Run them one at a
      time: six parallel fills nearly took the machine down.
- [ ] Mod jar rebuilt for the glitch price refs and checked in-game: yellow tiles still appear on a
      glitch-logic seed.
- [ ] `python tools/logic_selfcheck.py` passes.
- [ ] `python tools/verify_all_options.py` passes (no dead locks).
- [ ] `python tools/verify_reachability.py` passes.
- [ ] `python tools/check_compile_all.py` passes (every manifest compiles, nothing unreadable).
- [ ] `python tools/audit_bare_rules.py`: the backlog count is not higher than before (28 on 26.3 /
      32 on 26.1.2).
- [ ] Full `Generate.py` with the verify YAMLs **plus** BACAP, Overworld and
      Nether starts, on 26.1.2, 26.2 and 26.3.
- [ ] `gradlew build` for all 3 Stonecutter versions + `tools/check_mixin_targets.py`.
- [ ] In-game pass: D1–D5 (+ E items done), on a hosted test seed.
- [ ] Reply to the reporters with the B section.
