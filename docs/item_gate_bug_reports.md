# Item-gate bug reports — investigation notes (2026-09-06)

A player reported several advancements completing (or reading as reachable) despite gates that
should have blocked them. Reported against a YAML with `knowledge_gates: all`, `mob_spawn_lock` /
`structure_unlock: all`, `challenge_sanity: all`, `blazeandcave: true`, `item_gate_behavior` at its
default (everything gated except `given`), `biome_finder` / `structure_finder` disabled.

Reported symptoms:
- Potato and beetroot
- Wooden pickaxe
- "Item gates seem to not work correctly, especially crafting in solo inventories — should be
  gated behind crafting"
- Bookshelves & TNT
- Glow signs, but no Glow Squid unlocked
- Many redstone advancements

Working theory going in: the v0.5.1 "checks that cost nothing" fix (bare-rule audit, 62 → 47
UNGATED checks) tried to coerce more predicate shapes into real gates, and may have left a gap.
This doc records what was actually traced in the compiler source (`minecraft_aem/logic/`), what
was confirmed, and what remains open.

## CONFIRMED — farmland crops leak through `_acquire_from_sources`

File: `minecraft_aem/logic/acquisition.py`, inside `_acquire_from_sources`.

```python
placed_only = (bool(record.get("recipes")) or base.endswith(("_head", "_skull", "_froglight")))
...
if (block == base or (is_variant and placed_only)) and placed_only and base not in _NATURAL_SELF_MINED:
    continue   # circular self-mine — only structure-palette sources count
node = self._mining_node(block, base, stack=inner)   # otherwise falls straight through to here
```

`placed_only` is derived from `record.get("recipes")` — "does this item have a crafting recipe?".
That is the right question for a crafted block (TNT, bookshelf, redstone components — see below),
but potatoes and beetroot are **grown**, not crafted, so their acquisition records in
`minecraft_aem/packs/minecraft_26_1_2/acquisition.json` carry no `"recipes"` key at all:

- `potato`: `{"breeding": ["pig"], "chances": {...}, "drops": ["husk","zombie","zombie_villager"], "mining": ["potatoes"], "structures": [...]}`
- `carrot`: same shape, no `"recipes"`.
- `beetroot`: `{"breeding": ["pig"], "mining": ["beetroots"]}` — **no structures, no drops, nothing else at all.**

Because `placed_only` evaluates `False` for all three, the guard never fires, so `potatoes` /
`beetroots` / `carrots` fall straight into `_mining_node`, and `_block_origin_node` finds nothing
in `_BLOCK_ONLY_FROM` for them, so it returns a no-op. The compiled source is a bare
`access_region(Overworld)` node. These crop *blocks* never generate in the wild — only in a
village farm, or once the player has already planted them — exactly the same circularity that
`_BLOCK_ONLY_FROM` was introduced to fix for `nether_wart` / `wither_rose` / `carved_pumpkin` /
`tripwire`. It was never extended to farmland crops.

Effects:
- **`beetroot` is unconditionally free** right now — it has no other listed source, so with the bug
  its only path IS the bare region check, regardless of `structure_unlock` / `mob_spawn_lock`.
- **`potato` / `carrot`** still have real sources (village / pillager outpost / shipwreck
  structures, husk/zombie/zombie_villager drops), but `_unique_or`'s absorption rule
  (`A ∨ (A ∧ B) = A`) treats the bare region node as a subset of every one of those branches (each
  ANDs in the same `access_region(Overworld)` leaf), so it **deletes** the real structure/mob-lock
  gates from the compiled rule rather than just adding a redundant free option.
- **`wheat` is *not* affected**, by accident: its acquisition record includes an unrelated
  "uncraft a hay bale" recipe (`hay_block → wheat`), which makes `placed_only = True` and correctly
  routes it through the structures-only fallback.

### Suggested fix

Extend the same treatment `_BLOCK_ONLY_FROM` gives `nether_wart` to the farmland crop blocks
(`potatoes`, `beetroots`, `carrots`, and check `pumpkin_stem`/`melon_stem`/`torchflower_crop`/
`pitcher_crop` too — see `_FARMLAND_CROPS` in `logic/triggers.py`, which already recognizes these
as farmland-only for the *placing* direction but has no counterpart in the *acquiring* direction).
Either:
- add them to `_BLOCK_ONLY_FROM` with their real source (structures + mob drops — but
  `_BLOCK_ONLY_FROM`'s value shape doesn't currently support "mob drops", only `boss` /
  `structures` / `structures_or_craft`, so the shape may need a small extension), or
- key `placed_only` off farmland-crop membership as well as `record.get("recipes")`.

## Traced but NOT reproduced from current source

For the remaining five reports, the compiler logic as currently written looks correct on
inspection — no equivalent leak was found:

- **Wooden pickaxe**: `TOOL_LOCKS["wooden_pickaxe"] = (K_PICKAXE, MAT_WOOD)` in `data.py`; `acquire()`
  routes it through the `TOOL_LOCKS` branch in `acquisition.py`, ANDing
  `knowledge(K_PICKAXE)` with the crafting-station knowledge (via `_station_node` /
  `RECIPE_STATION_KNOWLEDGE["crafting"]`) and the ingredient sources. This is a single AND, never
  passed through `_unique_or`, so there's no absorption path to lose the gate.
- **Bookshelf / TNT**: both have non-empty `"recipes"` in `acquisition.json`, so
  `placed_only = True` and `block == base` correctly routes their `mining` entry into the
  structures-only fallback (which is empty/near-empty for these), leaving the recipe (glass+book,
  sand+gunpowder) as the real gate.
- **Redstone components** (observer, comparator, repeater, piston, sticky_piston, dispenser,
  redstone_lamp, hopper, dropper, daylight_detector, target): same shape as TNT/bookshelf — all
  have `"recipes"` and `block == base`, so they're correctly routed away from the bare self-mine.
  (Note: `redstone` dust itself has a *separate*, pre-existing quirk — its ore blocks
  `redstone_ore` / `deepslate_redstone_ore` get misclassified as "circular variants" by the
  `is_variant` check because `"redstone" in "redstone_ore"`, which drops the direct-mine shortcut —
  but the smelting/blasting recipe route still correctly requires the iron pickaxe via a nested
  `acquire()` of the ore item itself, so this makes logic *stricter* than the game, not looser. Not
  the reported bug, but worth a follow-up ticket on its own.)
- **Glow sign / Glow Squid**: `minecraft:husbandry/make_a_sign_glow` compiles via
  `_used_on_block_node` → `_item_predicate` → `acquire("minecraft:glow_ink_sac")` →
  `_acquire_from_sources` → mob-drop branch → `can_defeat(E_GLOW_SQUID)` → `entity()`, which
  correctly ANDs `has(ENTITY_UNLOCK_PREFIX + "glow_squid")` when `mob_spawn_lock` covers it
  (confirmed by reading `entity()` in `acquisition.py`).

If these are genuinely free in the reporting player's game, the cause is not visible in this
Python rule-compiler as it stands. Two candidates that need runtime/version info this doc can't
settle by itself:

1. **Version mismatch** — if the reporting seed was generated on an apworld older than v0.5.1
   (`37bf9b1`), it would have predated the `block == base` circularity guard entirely, which would
   explain TNT/bookshelf/redstone-component reports on their own. Need to confirm the
   `world_version` the seed was generated against vs. what's installed now.
2. **Java-side enforcement** (`archipelago-euclesia-minecraft/src/main/java/fr/euclesia/mcarchipelago/mixin/SlotMixin.java`)
   — this is what actually blocks taking a locked item out of a crafting grid in-game, separate
   from the AP logic tree entirely. It's written to cover both the crafting table and the player's
   own 2×2 grid (`menu instanceof AbstractCraftingMenu`, keyed off the menu class rather than the
   slot's container), and only exempts takes from `player.getInventory()` (the 36 main slots), not
   the crafting container. Static reading didn't turn up a bug, but this needs decompiled MC
   classes or a live test to actually confirm the classification holds at runtime for this MC
   version (`26.1.2` per `gradle.properties`).

## Item-gate report ("crafting in solo inventories")

`item_gate_behavior` in the reporting YAML was at its documented default
(`{crafting: true, station: true, container: true, pickup: true, given: false}` — see
`minecraft_aem/options.py`), so nothing unusual there. The "crafting" channel is explicitly
documented (and coded) to cover "the crafting table or your own 2x2" — see the docstring on
`SlotMixin.archipelago_euclesia$channelFor`. Confirming or ruling this out needs an in-game test:
with a knowledge lock active, try crafting something that fits the bare 2×2 personal grid (sticks,
torches, a crafting table itself) *without* ever opening a placed crafting table, and see whether
the lock message fires.

## Next steps

1. Fix the confirmed farmland-crop bug (`potato`/`beetroot`/`carrot`) in `acquisition.py`.
2. Confirm the `world_version` the reporting player's seed was generated against.
3. Run `tools/audit_bare_rules.py --all --markdown` and `tools/audit_predicate_coverage.py
   --markdown` against current `HEAD` (both currently require editing the hardcoded `AP_ROOT` path
   at the top of each script to point at a local Archipelago checkout) to regenerate
   `docs/bare_rule_backlog.md` / `docs/predicate_coverage.md`, which are stale (last regenerated at
   v0.5.0/v0.5.1) and may already show more of this class of bug than what's cited above.
4. In-game test of the personal 2×2 crafting grid against an active knowledge lock, per the
   section above.
