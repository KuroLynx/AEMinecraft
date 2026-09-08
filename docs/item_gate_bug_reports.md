# Item-gate bug reports — investigation notes (2026-09-06, resolved 2026-09-08)

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

Outcome, in one line: **four bugs fixed — a logic leak of a class wider than the report saw (26
items), an ungated villager, shears-free pale oak leaves, and a mod one that stood every station and
container gate open (sneak with empty hands) — plus two gaps that are design decisions, not defects:
item filler, and the ungated 2x2 grid.**

## FIXED — placed-only blocks with no recipe compiled to a bare region

File: `minecraft_aem/logic/acquisition.py`, inside `_acquire_from_sources`.

```python
placed_only = (bool(record.get("recipes")) or base.endswith(("_head", "_skull", "_froglight")))
...
if (block == base or (is_variant and placed_only)) and placed_only and base not in _NATURAL_SELF_MINED:
    continue   # circular self-mine — only structure-palette sources count
node = self._mining_node(block, base, stack=inner)   # otherwise falls straight through to here
```

`placed_only` asks "does this item have a crafting recipe?" as a proxy for "is this block only ever
here because something put it here". That proxy is right for a crafted block (TNT, bookshelf,
redstone components) and **wrong for every placed-only block you don't craft**, of which there turn
out to be four families. Each compiled to a bare `access_region(Overworld)`, and a bare region path
does not merely add a free option: `_unique_or`'s absorption rule (`A ∨ (A ∧ B) = A`) treats it as a
subset of every real branch (they all AND in the same region leaf), so it **deletes** the item's
structure and mob-lock gates.

| Family | Why it isn't a natural source | Now priced at |
|---|---|---|
| farmland crops — `potatoes`, `carrots`, `beetroots`, `wheat`, the stems, `torchflower_crop`, `pitcher_crop` | a crop is where somebody *planted a seed*; none generate in the open world | the seed (`_PLANTED_CROPS`), which has its own chest/mob/trade sources — and for potato/carrot the seed *is* the item, so the route resolves to the cycle it always was and drops |
| the grown sniffer plants — `torchflower`, `pitcher_plant` | same, one growth stage later | `entity(Sniffer)`, through the seeds |
| aged copper — `exposed_`/`weathered_`/`oxidized_` bars, chains, lanterns, lightning rods, golem statues | copper ages *in place*; you don't craft an aged block, you wait, so the dump has no `recipes` key for it | the plain item (`_AGING_PREFIXES` → `_aged_source`), plus any structure whose palette generates it already aged |
| `wet_sponge`, `decorated_pot` (→ `sherds`), `copper_golem_statue` | monument sponge room / a pot somebody assembled / a copper golem that finished oxidizing | `_BLOCK_ONLY_FROM`: the monument, the trial chambers, and a new `entity` kind for the golem |

Effect, measured with `tools/audit_bare_items.py` (new, below), everything on + BACAP:

```
before   144 of 1371 items ask for nothing
after    118 of 1371 items ask for nothing        (1 with no source at all, both runs: player_head)
```

The 26 that moved: `beetroot`, `beetroot_seeds`, `carrot`, `potato`, `poisonous_potato`,
`wheat_seeds`, `torchflower`, `pitcher_plant`, `sherds`, `wet_sponge`, `copper_golem_statue` and the
15 aged copper items. **`beetroot` was the worst of them — its record lists no source but the crop
block, so before the fix it was unconditionally free**, whatever `structure_unlock` /
`mob_spawn_lock` said.

One trap worth recording: routing an aged block through `acquire(plain item)` spends a level of
`_MAX_DEPTH` (4), and that was enough to starve the deepest chain in the data — waxed → exposed →
plain → copper torch → copper nugget → copper ingot — leaving the three waxed copper lanterns with
*no* source. Weathering is the same item later, not another crafting step, so `_block_origin_node`
resolves it against the caller's outer stack (`outer=`) and costs no depth. Cycles still terminate:
the plain item is on the stack for everything below it.

### Verification

- `tools/audit_bare_items.py` — 144 → 118 free, nothing newly sourceless.
- `tools/audit_bare_rules.py --all` — byte-identical before and after (no check changed bucket).
- Full-pool reachability, `everything on`, `everything on + BACAP`, `nether + everything on` — 0
  unreachable of 212 / 1183 / 212 locations.
- `tools/verify_reachability.py` — PASS (both start dimensions).
- `Generate.py` over `tools/verify_yamls/` (BACAP, nether, overworld, no-challenge) — full fill, 1794
  items placed, no cull failure on that seed.

## FIXED — a villager read as reachable with every village locked

Reported separately, and the same shape one level over: `entity()` gates a mob on its region, its
spawn-lock unlock, and — where one exists — a structure/parent/biome thunk. **`E_VILLAGER` had no
structure thunk**, so `Entity Unlock: Villager` alone was the whole price: `Kill Entity: Villager`
(and every rule that just wants a villager nearby) read in logic while all five villages were still
locked. A villager is only ever *in* a village; the one route that doesn't need one is curing a
zombie villager.

```python
E_VILLAGER: lambda: self.any_village(),
```

Curing a zombie villager is the one route that needs no village, and it is deliberately not modelled
— naming it in any form puts a villager back inside its own price. See "the trap this hit on the way"
below; ignoring the route only makes logic stricter than the game, which is the safe direction.

After both fixes: with only `Entity Unlock: Villager` in hand, `Kill Entity: Villager` is **out** of
logic; `pale_oak_leaves` and `pale_hanging_moss` ask for `Knowledge: Shear Handling`; with the full
pool nothing became unreachable (0 of 1183); `verify_reachability` PASS; the item audit still reports
118 free / 1 sourceless; the bare-rule report is byte-identical; and `Generate.py` over all four
verify YAMLs fills on two fresh seeds. The BACAP logic export grew 2744.7 → 2768.1 KiB (+0.8%).

### Same class, not changed — for a decision rather than a guess

Listing every mob whose rule is *only* region + unlock (`structure_bound_mobs`, `constructed_mobs`,
`parent_bound_mobs`, `biome_bound_mobs` all miss it) leaves 56 mobs, nearly all of which really do
spawn in the open world. Three don't, and each needs a call rather than an assumption:

- **Camel** — desert villages only in vanilla. Gating it on `structure(Village Desert)` is the exact
  analogue of the villager fix; I have not confirmed 26.1.2 kept that spawn rule, and `Camel Husk`
  (26.x) has no documented rule I could check at all.
- **Mooshroom** — mushroom fields only, which is the rarest biome in the game. That is a
  `needs_biome_finder()` case (like Creaking and Goat), not a structure one.
- **Zombie Horse** — no natural spawn in vanilla whatsoever. If that still holds in 26.1.2 its check
  is *impossible*, which is the opposite failure and would show up as an unreachable location, so it
  wants a dump-driven answer before anyone touches it.

## FIXED — pale oak leaves came without shears

Reported as "shears on leaves are in logic but there is no way to get shears". Ordinary leaves are
priced correctly — `block_mining.json` records `oak_leaves.drops.oak_leaves = ["shears", "silk"]`, and
`_drop_tool_node` turns that into `Knowledge: Shear Handling` (or Silk Touch). The Pale Garden's
blocks skipped all of it:

```python
if base in _PALE_GARDEN_BLOCKS:
    found = self.all_of(self.strict_only(self.needs_biome_finder()), self.access_region(REGION_OVERWORLD))
    return self.all_of(found, self.entity(E_CREAKING)) if base == "creaking_heart" else found
```

The branch **returned** the biome gate instead of adding it, throwing away everything
`_acquire_from_sources` knows about the block. For most of the family that changes nothing (a pale oak
log is a log), but `pale_oak_leaves` and `pale_hanging_moss` only drop to shears or Silk Touch — so a
pale leaf block cost a Biome Finder and nothing else, while an oak leaf block correctly cost the
Knowledge. Now the biome is ANDed with the block's own sources, and both ask for shears again.

### The trap this hit on the way (worth remembering)

The villager fix above first named the cure route as `reached("Advancement: Zombie Doctor")`. It reads
well and it type-checks, and every reachability check passed — but `Generate.py` died with
`RecursionError` deep in `fill_restrictive`'s sweep. Curing needs a golden apple and a Potion of
Weakness; those resolve through ingredients that list villager trades; `can_trade_villager()` leads
back to `entity(E_VILLAGER)` and so to the location that was supposed to justify it. **AP's
`can_reach_location` has no cycle guard**, so the rule tree recursed until the interpreter's stack gave
out — and only under a partial-item sweep, which is why a full-pool reachability check sails through
it. Villager is gated on villages alone; ignoring the cure route only makes logic stricter.

The lesson generalises: a `loc` reference inside a *mob or item* gate is a cycle risk whenever that
mob or item can appear anywhere in the referenced check's own price. `verify_reachability` and the
full-pool sweep cannot see it. **Only a real `Generate.py` run can.**

## NOT a logic bug — the other five reports

Each reported item was compiled under the reporting YAML's options and printed. They carry real
gates; nothing in the Python rule compiler lets them through:

| Reported | Compiled rule demands |
|---|---|
| Wooden pickaxe | `Knowledge: Pickaxe Handling` **and** (`Knowledge: Crafting Table` ∨ `Knowledge: Crafter`) |
| Bookshelf, TNT | (`Crafting Table` ∨ `Crafter`) — TNT additionally an `Entity Unlock` for the gunpowder mob |
| Redstone dust, repeater, comparator, observer, piston, dispenser | (`Crafting Table` ∨ `Crafter`), plus `Pickaxe Handling` + `Progressive Material Handling ≥3` on the ore route, `Structure Unlock: Ancient City` on the loot route; `dispenser` also `Knowledge: Dispenser` |
| Glow sign | `Entity Unlock: Glow Squid` (via `glow_ink_sac`, whose only source is the mob) |

So if those were genuinely free in the player's game, they were free **in the game, not in the
tracker** — and the server log the reporter attached shows how.

### 1. FIXED — sneaking with empty hands bypassed every station and container gate

`KnowledgeUseGate` skipped its check whenever `player.isSecondaryUseActive()` was true, on the
reasoning that "vanilla skips the block's own use when you sneak, because that is how you place
something against it". Vanilla's actual condition is stricter — disassembled from
`ServerPlayerGameMode.useItemOn` (26.1.2):

```java
boolean holdingSomething = !(mainHand.isEmpty() && offHand.isEmpty());
boolean skipBlockUse = player.isSecondaryUseActive() && holdingSomething;
```

**Sneaking with two empty hands still opens the block.** So: crouch, empty your hands, right-click —
and a crafting table, chest, barrel, furnace, enchanting table, brewing stand, dispenser or shulker
box you have no Knowledge for opens exactly as normal. No message, nothing in the log. It is not an
obscure input either: sneaking near a block is what players do all day.

That one hole accounts for the whole "bookshelves & TNT / many redstone advancements" cluster. The
log is explicit about the window: the player had **no crafting-station Knowledge at all** until
`Knowledge: Crafter` at 16:26 (`Knowledge: Crafting Table` was still unfound at 17:23, when they
hinted it) — yet they were completing craft-only checks from 16:01.

Fixed: `placingAgainstBlock()` now mirrors vanilla's condition exactly, so the gate passes precisely
the interactions vanilla was never going to route to the block anyway. `./gradlew compileJava` clean;
**needs a jar rebuild and an in-game check** (crouch with empty hands at a gated table → red
"Requires Knowledge: …" and no GUI).

### 2. Filler items hand out gated goods, and the log catches it three times

Filler in `content/filler.csv` is buffs, traps **and items** — slabs, stairs, signs, boats, chests,
barrels, dirt, sticks, apples. Those arrive as Archipelago items, so no gate sees them, and several
BACAP checks are a bare `inventory_changed` on exactly such an item. From the reporter's log:

| Time | Filler received | Check completed | The check's criterion |
|---|---|---|---|
| 16:01:05.184 | `Mossy Stone Brick Slab x16` | 16:01:05.677 — **Cut In Half** | have any `#minecraft:slabs` |
| 16:20:02.265 | `Mossy Stone Brick Stairs x16` | 16:20:02.744 — **Stairs? NOOOOO!** | have any `#minecraft:stairs` |
| 16:00:45.691 | `Pale Oak Boat x1` | 16:01:39.041 — **Boaty McBoatface** | ride a boat |

Half a second apart, twice. In logic every one of those items costs (`Crafting Table` ∨ `Crafter`);
in play they were handed over. It cannot break generation — filler is never required — but it is
exactly what "item gates seem to not work" looks like from the player's chair, and it cascades: the
check a filler item completes pays out more filler.

Worth a decision: the buff filler already in `filler.csv` was introduced for an adjacent reason
(BACAP keys goals off almost every item), and dropping the item rows in favour of it would close
this without any new mechanism.

### 3. The player's own 2x2 grid is not gated by the crafting Knowledge

`_station_node` charges (`Crafting Table` ∨ `Crafter`) for **every** `crafting_*` recipe, deliberately
— the dump doesn't record a shaped recipe's grid size, so it can't tell a 2x2 recipe from a 3x3 one
and takes the strict reading. In game there is no such check: `SlotMixin` routes a crafting-result
take to `MaterialLockService`, which only knows **material tiers and tool locks**, and
`KnowledgeUseGate` gates the crafting *table block*. Nothing gates the inventory's own 2x2 grid,
because it is not a block.

So every non-wood recipe that fits 2x2 — torch, redstone torch, sticky piston, book, sugar, a
glowstone block, the crafting table itself — is free in game and priced at a Knowledge in logic.
(Wood is already carved out on the logic side: `_wood_region` in `_acquire_compute` makes planks,
logs and sticks cost nothing but their dimension, which is exactly the 2x2 reasoning.)

This is the player's third report, and it is real. Closing it means gating the 2x2 result slot on
the crafting-station Knowledge, which needs the *set* that satisfies it — `RECIPE_STATION_KNOWLEDGE`
lives only in Python today, so the fix is a new slot-data field (plus `SLOT_DATA_VERSION`, a jar
rebuild and an in-game test). Left undone deliberately: it changes how a seed plays, so it is the
maintainer's call, not a bug fix.

### Checked and ruled out

- **BACAP rewards** — off in the reporting seed (confirmed by the reporter), so `BacapRewardService`
  never ran. Worth remembering for future reports all the same: it only pulls back items
  `MaterialLockService` refuses (materials and tools), so a bookshelf, TNT, a redstone component or a
  glow ink sac would ride through untouched when rewards *are* on.
- **The slot data itself** — dumped for the reporting option set and read: 146 `tool_locks`
  including `minecraft:wooden_pickaxe → Knowledge: Pickaxe Handling`, 38 `material_handling_locks`
  (cobblestone/stone at tier 1 up to netherite), 64 `station_knowledge_locks` including
  `minecraft:crafting_table`, `item_gate_behavior` at the documented default, and
  `minecraft:glow_squid` present in `mob_spawn_lock_mobs`. Nothing missing, nothing misspelled, and
  the Java parsers read each of those shapes correctly.
- **`ResultSlot` overriding `mayPickup`**, which would silently disable the crafting take gate — it
  does not, and `AbstractContainerMenu.doClick` calls `mayPickup` six times (pickup, shift-move,
  swap, throw).
- **The in-game tracker's evaluator.** `tools/logic_selfcheck.py` passes on all four item sets. The
  one place `LogicEvaluation` deliberately diverges from that validated model is `solve()` seeding
  every CHECKED location as reachable ("history, not a prediction"), which could in principle let one
  check completed through an in-game leak cascade optimism down the parent chain. Replayed against
  the reporter's own session — their 14 received items and all 62 checks from the log — it gains
  **0** locations over the rules alone (153 → 165 in logic, every one of the 12 a check they had
  already completed). The seeding is not the leak.
- **Their tracker view, replayed.** With exactly the items the log shows, before `Knowledge: Crafter`
  arrived at 16:26 the reported checks are all **out** of logic (`Click!`, `Under Pressure`,
  `Bombs Away!`, the bookshelf and redstone-component ones, `Stone Age`, `Getting an Upgrade`); after
  it, `Click!`, `Under Pressure`, `Bombs Away!` and `Librarian` come into logic legitimately —
  **`Knowledge: Crafter` satisfies the crafting gate exactly as `Knowledge: Crafting Table` does**
  (`RECIPE_STATION_KNOWLEDGE["crafting"]` is both). Worth saying to the reporter: a seed can hand you
  the Crafter first, and from that moment every recipe is in logic without the crafting table ever
  being found.

### Still unexplained

**Wooden pickaxe** and **glow signs without Glow Squid**. Both are gated everywhere the code can see:
the pickaxe by a tool lock on every take route (the sneak bypass opens a table, but the result-slot
take is still refused), and glow ink sacs by a spawn-locked mob that is correctly listed in the slot
data. What is left are routes the log can't settle — an operator `/give` (the `given` route is
ungated by default), creative mode on a shared server, or a window where
`AEMServerRuntime.isArchipelagoReady()` was false. Note that **every gate fails open while that is
false** (`MaterialLockService`, `KnowledgeLockService` and the spawn/structure services all return
"allowed" early), so any period the world ran without slot data is a period with no gates at all —
and whatever was taken then is kept.

## Still open

1. **The 2x2 crafting gate** (mod side, design decision) — see above.
2. **Item filler** — keep it, or drop the item rows in favour of the buff filler (design decision).
3. **Ask the reporter** how the wooden pickaxe was obtained, whether anyone on that server had
   operator or creative, and whether the mod logged a disconnect (the fail-open window).
4. **`tools/audit_bare_rules.py --all --markdown` / `audit_predicate_coverage.py --markdown`** still
   need regenerating into `docs/bare_rule_backlog.md` / `docs/predicate_coverage.md` (both stale at
   v0.5.0/v0.5.1). The rule audit is unchanged by this fix, so the 42-entry BACAP UNGATED list stands
   as the remaining backlog.
6. **`redstone_ore` / `deepslate_redstone_ore` are misclassified as circular variants** by the
   `is_variant` check (`"redstone" in "redstone_ore"`), dropping the direct-mine shortcut. The
   smelting route still requires the iron pickaxe, so this makes logic *stricter* than the game —
   cosmetic, but its own ticket.

## The instrument

`tools/audit_bare_items.py` is new, and is what found the four families rather than just the crops.
It applies the bare-region test to `acquire()` instead of to advancement rules, and prints each free
item's listed sources so the two reasons an item reads free — genuinely free (kelp, dirt, a poppy)
versus circular (a crop, an aged copper block) — can be told apart by eye. Run it after any change to
the acquisition tables or a new content dump:

```
python tools/audit_bare_items.py            # everything on + BACAP, the decisive config
python tools/audit_bare_items.py --all -v   # every config, with each compiled rule
```
