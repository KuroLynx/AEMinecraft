

  Detailed list of the 63 remaining BACAP parent-chain advancements

  location (21)
  
  Milestones (15) — type_specific.advancements, each aggregates a set of prerequisite advancements; at least one in each set is excluded this seed
  (challenge/statistics/technical tab), so it can't be gated on an AP location:
  Adventure, Animal, Biomes, Building, Enchanting, End, Farming, Mining, Monsters, Nether, Potions, Redstone, Statistics, Super Challenges, Weaponry Milestone
  → HARD (could approximate by gating only on the active subset, but that's looser than reality).

  Other location (6):
  - Heart of Darkness — light level ≤0 → not a logic gate (could region-gate)
  - Marine Marauder / Stayin' Frosty — effect (water-breathing/fire-res) + fluid → HARD (effect-in-fluid)
  - Smooth Operator — stepping on #minecraft:ice (a block tag, my expander only does item tags) → MEDIUM
  - Llama Festival — ride a carpeted llama (nested vehicle+equipment) → MEDIUM
  - Must be your birthday — eat 100 cake (custom stat) → HARD/intractable

  impossible (18) — INTENTIONAL, leave as-is

  Granted by BACAP's own scoreboard logic, no gameplay prerequisite: Art Gallery, Artillery, Battle of the Bands, Beef Moover, Chestful of Cobblestone, Chick Buffet, Copper
  Golem Overlord, Dragon vs Dragon, Free Diver, Full Stomach, Half Heart Life, Loser!, Lucky Break, Redemption Arc, Sound the Alarm!, The Three Little Pigs, Vault Hunter, Why do
  I hear boss music?

  inventory_changed (7)

  - A Suspicious Advancement (suspicious sand/gravel), Green Lanterns (copper_lantern), Off With His Head! (dragon_head), Tricky Treasures (music_disc_creator) → acquisition
  gaps (items have no modeled source)
  - Chromatic Armory, Coordinated Flair — armor trim component → HARD
  - Explosive Fire — nbt string (charged crossbow) → intractable

  placed_block (4) — CHEAP FIX (crop→seed mapping)

  Come to the countryside! (wheat), Ecologist (oak_sapling), Hot Chocolate (cocoa→cocoa_beans), In Your Face, Neil Armstrong (potatoes) — same crop-block-isn't-an-item issue as
  torchflower; extend _PLANT_ITEM.

  started_riding (3) — CHEAP FIX

  Boatception, Boaty McBoatface, Cargo Carrier — vehicle #blazeandcave:all_boats/chest_boats tags aren't in the item-tag table; map boat tags → acquire a boat.

  player_killed_entity (3)

  - Nice to Mace You!-style isn't here; Gas! (lingering-potion cloud), Final Shout (victim effects) → HARD
  - Trick or Treat! (victim wears carved pumpkin) → MEDIUM (carved pumpkin + any mob)

  container_loot (2) — CHEAP FIX

  Dungeons and Spawners (simple_dungeon), Steals on Wheels (abandoned_mineshaft) → map table → structure.

  player_hurt_entity (2)

  - Nice to Mace You! — mace_smash damage tag → CHEAP (acquire mace)
  - I'm in Lava With You — distance/strider combo → HARD

  singles (3)

  - So I got that going for me (bred_animals, #blazeandcave:llamas tag) → MEDIUM (tag expand)
  - Living Dummy (entity_killed_player, armor_stand) → intractable
  - Locked and Loaded (item_used_on_block, shelf states) → HARD

  Summary

  - 18 intentional (impossible) — should stay.
  - ~11 cheap wins: placed_block crops (4), boat tags (3), container_loot (2), mace_smash (1), llama bred (1) → would take 63 → ~52.
  - ~34 hard/intractable: the 15 Milestones, trim/nbt/stat/effect/item-gap cases.

  Want me to do the ~11 cheap wins (then we're realistically done), or stop here?

❯ For the milestone, they should never be active locations !                                                                                                                     
  heart of darkness is a legit Const(True) advancement                                                                                                                           
  effect-in-fluid only requires accessing the effect                                                                                                                             
  we can add blocks tag                                                                                                                                                          
  Llama Festival is fine to implement                                                                                                                                            
  eat 100 cake ? then just have access to cake                                                                                                                                   
                                                                                                                                                                                 
  Let's add all cheap / medium / hard fixes, we'll see about what is still not tracked

---

## Results (2026-06-23)

BACAP fallbacks: **76 / 1012** (was ~88). Of those 76: **50 `impossible`** (intentional) +
**2 tab roots** (correctly region-gated) + **24 genuinely intractable**. Full BACAP generation
(`accessibility: full`, `challenge_sanity`, all mob/struct locks) succeeds; vanilla still 122/125.

### Implemented
- **Milestones** (data.py): `skip_frame_tabs={"bacap": {goal, challenge}}` — per-tab Milestones /
  Advancement Legend are no longer locations. Per-tab `goal` advancements (Llama Festival etc.) stay.
- **Block tags** (build_tags.py): now emits a `block` registry; regenerated both tags.json.
  `_expand_item` falls back to block tags → Smooth Operator (`#minecraft:ice`).
- **Heart of Darkness**: `location.light` (and `fluid`) treated as non-gates → trivially true.
- **Marine Marauder / Stayin' Frosty**: `effects` predicate inside a `location` → effect source
  (brewing); the paired fluid condition is dropped.
- **Llama Festival / Boatception / Boaty / Cargo Carrier**: `_vehicle_node` (mount mob OR boat item,
  worn equipment, pinned structure, passenger); `started_riding` delegates nested forms to `_player_node`.
- **Must be your birthday**: custom stat `eat_cake_slice` → `acquire(cake)` via `_CUSTOM_STAT_ITEM`.
- **Ring of the End / Iceologer**: `killed` stat type → `can_defeat(mob)`.
- **placed_block crops**: `_PLANT_ITEM` extended (wheat/beetroots/carrots/potatoes/stems/cocoa/bamboo);
  fire/soul_fire → flint_and_steel **or** fire_charge (list values now supported).
- **container_loot**: segment-scan + alias map (simple_dungeon→Dungeon, abandoned_mineshaft→Mineshaft,
  woodland_mansion→Mansion, underwater_ruin→Ocean Ruin, trial_chambers subdir) → Dungeons and Spawners,
  Steals on Wheels, I am Loot.
- **bred_animals** tag expansion → So I got that going for me (#blazeandcave:llamas).
- **Nice to Mace You!**: `_mainhand_weapon` reads `player.equipment.mainhand`.
- **Trick or Treat!**: player_killed victim pinned only by worn equipment → can_kill + worn item.
- **There it goes…**: projectile `killing_blow` (is_projectile tag) → bow/crossbow + arrow.

### Still NOT handled (fall back to parent-chain / region — all intentional)
- **`impossible` (50)**: BACAP scoreboard-granted, no gameplay prereq in the criterion.
- **`inventory_changed` (14)**: armor-trim component (Chromatic Armory, Coordinated Flair); nbt string
  (Explosive Fire); "have ALL items/blocks" challenges (All the Items/Blocks, Stack…, Dragon Army);
  acquisition-data gaps (A Suspicious Advancement, Green Lanterns=copper_lantern, Off With His Head!=
  dragon_head, Tricky Treasures=music_disc, Pottery Exhibition / sus = sherds).
- **roots (2)**: bacap/root, biomes/root — region-reachable is correct.
- **time stat**: Insomniac (`time_since_rest`) — pure wait, no gate.
- **technical-tab deps**: Riddle Me This (needs `technical/riddle_*_line`, a skipped tab).
- **victim potion effects**: Gas! (lingering cloud), Final Shout — BACAP-applied effects on the victim.
- **misc intractable**: Living Dummy (armor_stand kills player), Locked and Loaded (shelf block-states),
  I'm in Lava With You (distance/strider), Multiclassed (area_effect_cloud + nested mainhand weapons),
  Dimension Penetration (nbt-tagged arrow), A Furious Test Subject (mob carrying every effect).

## Round 2 (2026-06-23) — fallbacks 76 → **69**

Per user follow-up, tightened/added more:
- **Insomniac** (`time_since_rest`) + **time_since_death** → `_CUSTOM_STAT_TRIVIAL` = `and_()` (pure wait).
  Also: `_predicate_loc_node` now routes a list-form `player`'s nested `type_specific` (Insomniac's
  stat sits in an `entity_properties` predicate, not directly on `player`).
- **Living Dummy** (`entity_killed_player`, entity.type=armor_stand) → new `_entity_killed_player_node`:
  acquire(armor_stand) ∧ Knowledge: Armor Handling ∧ enchanting table ∧ iron material (per user spec).
- **I'm in Lava With You** (`player_hurt_entity`, victim has NO type — only `distance` + lava `fluid`)
  → `_has_lava_fluid` detects the lava condition → `can_kill_any_mob()`. (NOT Strider/Nether: lava
  pools exist in the Overworld too, so forcing the Nether was too restrictive — any reachable mob you
  can hit bare-handed satisfies it.)
- **Multiclassed** (`player_hurt_entity` ×23, one per weapon) → `_weapon_node` now handles a **list**
  `direct_entity.type` and reads the attacker's **`source_entity.equipment.mainhand`** (preferred over
  generic can_kill); `_projectile_item` maps `area_effect_cloud`/`lingering_potion` → lingering potion.
- **Gas!** (`player_killed_entity`, killing_blow direct_entity=area_effect_cloud) → lingering potion (same map).
- **Dimension Penetration** (`entity_hurt_player`, arrow nbt `{Tags:[dimpen_overworld,nether,end]}`) →
  `_dimpen_node` parses the dimpen tags → bow/crossbow + arrow ∧ access to each named region.
- **Locked and Loaded** (`item_used_on_block`, `#minecraft:wooden_shelves` in nested any_of/all_of) →
  `_blocks_in` now recurses into `terms` → acquire a wooden shelf.

Still falling back (all intentional): 50 `impossible`, 14 `inventory_changed` (trim/nbt/all-items/
acquisition-gaps), 2 roots, **Riddle Me This** (depends on skipped `technical` tab), **Final Shout**
(victim must carry wind_charged+weaving+oozing simultaneously), **A Furious Test Subject** (attacker
carries all 17 potion effects — parent = vanilla `all_potions`/A Furious Cocktail, a good gate).
Full BACAP gen (accessibility:full) PASS; vanilla 122/125.

## Round 3 (2026-06-23) — enter_block dimension leak (vanilla + BACAP)

User caught it: `enter_block`/`*_block_use` returned `and_()`→`Const(True)` for any non-item natural
block, *relying on region placement to gate it* — but EVERY advancement (vanilla too) is placed in the
**Overworld** region, so a dimension-only block leaked as reachable-from-spawn. Confirmed leaks:
vanilla **Remote Getaway** (`end_gateway`, parent kill_dragon) + BACAP **The End?** (`end_portal`),
**We Need to Go Deeper** / **Ancient Restoration** (`nether_portal`), **Twisted** (`twisting_vines`),
**Don't Blink** (`weeping_vines`), **Burnt Right Into Your Soul** (`soul_fire`), **Super Challenges**
(`end_gateway` OR-branch).

Fix: `_BLOCK_REGION` map (end_portal/end_gateway→End; nether_portal/soul_fire/twisting_vines[_plant]/
weeping_vines[_plant]→Nether) + `_block_region_node`; the block-use handler checks the region map
**first** (`_block_region_node(blocks) or _any_acquire(blocks)`) — authoritative for dimension-pinned
blocks, and it also masks an acquisition-data bug (twisting_vines/weeping_vines are mis-modelled as
Overworld-obtainable; **a real bug to fix in build_acquisition — Nether-only plants**). Now Remote
Getaway → `region The End`; the BACAP six → their dimension. verify_reachability PASS, full BACAP gen
(accessibility:full) PASS, vanilla still 122/125.

KNOWN REMAINING acquisition-data bug (separate subsystem): `acquire(twisting_vines)` /
`acquire(weeping_vines)` resolve to **Overworld** — should be Nether. Masked here for enter_block, but
would still leak via any `inventory_changed`/item path that needs those vines.

## Round 4 (2026-06-23) — dimension-gating audit (vanilla + BACAP)

User directive: EVERY advancement must gate to its true dimension(s) (rule-level), because the start
dimension can change (nether start); but multi-dimension advancements (kill any mob, travel N blocks)
must stay completable in ANY dimension, not pinned to one.

KEY ARCHITECTURE FACT: **all 1137 advancement locations are placed in the Overworld region** (vanilla
& BACAP — `load_manifest_advancements` default region, never overridden). So per-advancement dimension
gating comes ENTIRELY from the compiled RULE's `access_region` nodes, NOT from placement.

Leak sweep (overworld start, full pool minus Nether+End unlocks → those regions unreachable; list
advancements still reachable that need that content) found systemic under-gating beyond enter_block:
- **`material(NETHERITE)`** returned only `has(Progressive Material Handling ×6)` with NO region —
  `acquire()` added the Nether separately (so acquire was fine) but curated/compiled rules calling
  `material(NETHERITE)` directly leaked. FIXED in acquisition.py: `material(tier>=NETHERITE)` now ANDs
  `access_region(Nether)` (single source of truth). Closes Cover Me in Debris, Serious Dedication, +
  every netherite-tier BACAP adv (Master Armorer, MOAR Upgraded Tools, …).
- **Biome `location` predicate** gated only on `needs_biome_finder()` ("placement gates the dimension"
  — but placement is Overworld). FIXED: `_biome_region(id)` (`_NETHER_BIOMES`/`_END_BIOMES` sets) →
  the biome's region is AND-ed in. Closes Hot Tourist Destinations (all-Nether-biomes).

Verified: those leaks now reachable_with_nether/end_locked = False; vanilla 122/125; verify_reachability
PASS; full BACAP gen (accessibility:full) PASS.

RULE-LEVEL STRAGGLERS — RESOLVED:
- **Two by Two** (vanilla bred_all_animals) FIXED: the `bred_animals` handler read `parent`/`partner`
  via `pair.get("type")` (dict only) but frog/sniffer/turtle pin the species on the entity_properties
  **list** form (their breeding yields a tadpole/egg, not a `child`) — switched to `_predicate_value`
  (handles list + dict), and use `can_breed` for any pinned species (not just MOBS_BREEDABLE-flagged).
  Now compiles to the full AND incl. can_breed(strider/hoglin) → requires Nether. Vanilla 122→**123/125**.
- **Not Quite "Nine" Lives** — NOT a leak (false positive). `acquire(glowstone)` has a legit Overworld
  path (witch-dropped glowstone dust → craft), and crying obsidian comes from Overworld ruined portals,
  so a respawn anchor is genuinely Overworld-craftable+chargeable. Correctly Overworld-reachable.
- **Over-Overkill** (`minecraft:adventure/overoverkill`) FIXED: a mace smash pins the mace on
  `damage.type.direct_entity.equipment.mainhand` (direct_entity.type="player"), but `_weapon_node` only
  read `source_entity`'s mainhand and treated direct_entity as a projectile → `acquire("player")`=None.
  Now `_weapon_node` reads a wielded mainhand from the direct OR source entity FIRST (a real projectile
  has no mainhand, so it still falls through) → compiles to acquire(mace). **Vanilla now 124/125** — the
  only remaining non-compile is `adventure/root` (tab root, correctly Const(True)).
- **Adventure root** (`minecraft:adventure/root`) FIXED — was NOT a harmless Const(True): its OR is
  `player_killed_entity{}` (kill any mob) OR `entity_killed_player{}` (be killed by a mob) — BOTH need
  a MOB, not environmental death. Under **mob_spawn_lock** every mob is gated behind its unlock, so
  Const(True) leaked (root reachable before unlocking any mob). Empty `player_killed_entity` /
  `entity_killed_player` now compile to `can_kill_any_mob()` (OR over reachable non-boss mobs).
  Verified: with all mob categories locked the root is unreachable until one Entity Unlock is received.
  **Vanilla now 125/125 — every advancement compiles.** verify_reachability + full BACAP gen
  (mob locks on, accessibility:full) PASS.

## Round 5 (2026-06-23) — PLACEMENT BINDING + material/tool region floors (uncommitted)

User chose systemic placement, with multi-dimension checks (kill any mob, travel) NOT pinned to one
dimension. Implemented:

**Placement derived from the rule** (logic/root.py `build_location_rules` + `derive_location_regions`,
wired in __init__.create_regions; logic_export ships the placed region). Each location is placed in the
region its rule REQUIRES (removing it makes the rule unsatisfiable): deepest of End>Nether>Overworld,
or the always-reachable origin (Menu) when no single dimension is required (multi-dim OR / item-only).
Placement now only reinforces a dimension the rule already needs — never the old spurious uniform-
Overworld floor that over-gated Nether/End checks and broke Nether start. Rules are cached so
create_regions and set_rules use the same ones. Sample: Nether root→Nether, The End?/Remote Getaway→
The End, netherite/nether-biome→Nether, Adventure(kill-any-mob)/Husbandry→Menu.

**Material region floors** (acquisition.material): copper/iron/diamond→Overworld, stone/gold→
Overworld|Nether (cobblestone/blackstone, OW/nether gold), netherite→Nether; wood(tier 0)=trivially
true. Single source of truth so every caller inherits the floor.

**Tool/armor acquisition fix (the big one)**: `acquire(tool)` used to short-circuit to a lossy
`knowledge + material(tier)` proxy, DISCARDING the item's real sources (recipe ingredients, structure
loot, trades, drops) — so a fishing rod was just `Knowledge: Fishing + material(0)` with NO region.
Now tools resolve as `knowledge(K) AND <real source OR-tree>` (factored into `_acquire_from_sources`,
shared with ordinary items; coarsened so tool rules stay compact). `_gameplay_node` now threads the
recursion stack so a circular source (fishing UP a fishing rod, bartering FOR gold) breaks instead of
recursing. Now: fishing_rod→Fishing+Overworld, crossbow→Sharpshooter+(Nether|Overworld bastion loot),
shears→Shear Handling+Overworld.

**Verified**: vanilla 125/125; verify_reachability PASS both starts; full BACAP gen PASS for BOTH
overworld AND nether start (accessibility:full, all mob locks, challenge). Nether-start section B went
0→31 reachable (over-restriction fixed); the 31 are nether-legit (incl. Bastion loot for crossbow/
diamond gear/books).

## Round 6 (2026-06-24) — Overworld/water block region floors + froglight

Closed the Nether-start block-condition leaks the placement refactor exposed:
- `_BLOCK_REGION` extended (values now region TUPLES = OR): powder_snow/sweet_berry_bush/dirt_path →
  Overworld; water/bubble_column/water_cauldron → Overworld|End (water never exists in the Nether);
  existing portal/vine/soul_fire → Nether/End. `_block_region_node` ORs over all regions any listed
  block can be in. Now consulted by `stepping_on` (Light as a Rabbit) and `_used_on_block_node`
  (Pathways) too, not just enter_block — region is checked BEFORE item acquire (authoritative), and
  ANDs with the criterion's other parts so an Overworld-block-in-Nether adv keeps both requirements.
- **Froglight** acquire special-cased: `all_of(entity(Frog), entity(Magma Cube))` = AND(Overworld,
  Nether) — a frog (OW-only spawn) eats a magma cube (Nether). The table mis-modeled it as a plain
  magma-cube drop, dropping the frog. Fixes With Our Powers Combined!.

Verified: vanilla 125/125, verify_reachability PASS, full BACAP gen PASS BOTH starts. Closed: Light as
a Rabbit, This Snow is Snowier, Polar Opposites, Dive Bomb, Just Keep Swimming, Hot Spring, Pushed
Around, Disen Berry Berry Bad!, Pathways, With Our Powers Combined!.

KNOWN-FALSE-POSITIVES (legitimately Nether-doable, NOT leaks): The Power of Books (chiseled bookshelf
= crimson planks + Bastion/Fortress-loot books; comparator = nether quartz), barrel/buttons/composter/
walls/pressure-plates/tripwire/shelves (crimson/blackstone craftable).

REMAINING long-tail (per-advancement criterion-shape gaps, user-flagged):
- **placed_block adjacent conditions dropped**: The Power of Books' criterion needs a COMPARATOR
  adjacent to the placed chiseled_bookshelf, but `_placed_block_node` ORs all blocks (incl. the
  adjacent comparator pulled in by the `_blocks_in` terms-recursion) as placement ALTERNATIVES instead
  of AND-ing adjacent-block location conditions. No leak here (both Nether-craftable) but a real gap.
- **Stay Hydrated!** still reachable Nether-only (hydrating a dried ghast needs water → Overworld; the
  water condition isn't captured).