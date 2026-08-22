# AEMinecraft

## Why "AEMinecraft" and not just "Minecraft"?

There is **no official Minecraft implementation** in Archipelago. **AEMinecraft** is a standalone,
community-made randomizer (by KuroLynx, mod by EDGN) for **modern Minecraft (Java Edition 26.1.2)**.
The `AEM` prefix identifies this specific mod and apworld rather than claiming the bare "Minecraft"
name, so it stays unambiguous and leaves room for other implementations that may appear later.

## Where is the options page?

To configure and export a config file, either download the `template.yaml` from the
[latest release](https://github.com/KuroLynx/ArchipelagoMinecraft/releases/latest) and edit it, or
generate a template with the Archipelago Launcher once the AEM apworld is installed. Every option is
documented with inline comments.

## I haven't played Minecraft before.

<u>**Play vanilla first.**</u> This randomizer assumes you already know how to progress through
Minecraft — mining, the Nether, brewing, the End, and so on. It is strongly recommended that you are
comfortable beating the Ender Dragon on your own before playing the randomizer.

This matters because the gating can make even **basic things surprisingly roundabout**. Depending on
your options and how the seed shuffles out, you might have to obtain something as ordinary as string,
or reach a mob you want to kill, through a far more obscure path than in vanilla — a locked mob won't
spawn until you receive its unlock, a locked structure stays sealed, a material can't be picked up
until you've received enough Material Handling, and a whole dimension can be off-limits until its
Dimension Unlock arrives. Knowing the vanilla routes to everything makes it much easier to recognise
these alternative paths when the obvious one is blocked.

If you enable **BlazeandCave's Advancements Pack (BACAP)**, familiarity with that datapack helps too,
since many of your checks come from it.

## What does randomization do to this game?

Instead of shuffling item pickups, AEMinecraft turns **advancements and mob kills into location
checks** and hands progression back to you as Archipelago items. Completing a check anywhere in your
world releases the item held in that location — which may belong to you or to another player.

Progression is gated by items you receive from the multiworld. Depending on your options, these can
include:

- **Dimension Unlocks** — you cannot enter the Nether or the End until you receive the matching
  unlock (portals will not work otherwise).
- **Structure Unlocks** — locked structures stay sealed until unlocked; anything (and any mob) inside
  is preserved and placed when the unlock arrives.
- **Entity Unlocks** — locked mobs will not spawn anywhere until unlocked. Bosses can be held back
  this way too.
- **Progressive Material Handling** — you cannot obtain gated materials (diamond, etc.) until you have
  received enough copies. The `item_gate_behavior` option decides which routes that blocks: taking it
  from a crafting grid, from a workstation (furnace, anvil, trade, …), from storage (chest, barrel, …),
  picking it off the ground, and `/give`. By default every route is blocked except `/give` — note that
  BlazeandCave's item rewards ride that `/give` route, so gate it if you want those gated too.
- **Knowledge** and **Tool/Armor** gates — some crafting stations (enchanting table, brewing stand)
  and some tools/armor require a Knowledge item, and better tools also require enough Material
  Handling. These obey the same `item_gate_behavior` routes. The `knowledge_gates` option picks which
  Knowledge gates your run uses at all — by category (`tool`, `armor`, `misc`, `station`, `container`),
  one by one, or `All` — and any entry written with a leading `-` (e.g. `-Chest`) switches that gate
  back off, so you can take a few out of a preset. A station/container gate blocks both crafting the
  block and using it, including ones you find in the world. The default is everything except the
  chest, crafting table and furnace.
- **Progressive Villager Trust** — villagers refuse to trade until you unlock each of the five trade
  levels (optional).

Optional systems expand or reshape the game:

- **Kill Sanity** — the first time you kill each mob is a check.
- **Challenge Sanity** — the hardest (challenge-frame) advancements become checks.
- **BlazeandCave's Advancements Pack** — adds BACAP's many custom advancements as checks.
- **Start Dimension** — start in the Overworld (classic) or trapped in the Nether.
- **Structure Finder / Biome Finder** — navigation aids (see below) that can be shuffled into the
  pool, granted at start, or disabled.

## What is the goal of AEMinecraft when randomized?

You must satisfy **two** conditions (whichever your options set):

1. **Defeat the required bosses.** By default this is the **Ender Dragon**, but the `boss_list`
   option lets you require any of the Ender Dragon, Wither, Elder Guardian, and Warden — or `All`.
2. **Complete a required number of advancements.** The `advancements_required` option (default 75)
   sets how many advancement checks you must complete. Set it to 0 to make boss kills the only goal.

If you ask for more advancements than your options actually make available, the requirement is
automatically lowered to fit, so a high value is always safe.

## What items from AEMinecraft can appear in another player's world?

Every item in the pool can appear in another player's world — Dimension/Structure/Entity Unlocks,
Knowledge and Material Handling items, the Structure and Biome Finders, and filler (see below).

## How many checks are in AEMinecraft?

There is no fixed number — it depends on your options. The baseline is the vanilla advancements plus
your required boss kills. **Kill Sanity**, **Challenge Sanity**, and **BlazeandCave's Advancements
Pack** each add many more checks. The recap printed while a game is being generated reports your total
check count, and during a session a room admin can run the `/status` command to see how many checks
each slot has and how many remain.

## What do checks and received items look like in-game?

Checks are **advancements** (and, with Kill Sanity, **first-time mob kills**), not item pickups in the
world — so there are no special chests or freestanding tokens to find. Completing the underlying
advancement or kill sends that location's item to whoever it belongs to.

Received items are applied automatically by the mod when they arrive:

- **Filler** grants a temporary mechanical **buff** or a small **item stack**.
- **Traps** fire a one-shot negative effect. The `trap_chance` option controls how often filler is
  replaced with a trap.
- **Unlock / Knowledge / Material items** quietly remove the corresponding gate.

## Is there a tracker?

Yes — it is built into the mod. An in-game **Archipelago advancement tab** tracks your progress, and
advancements that are currently in logic are highlighted, so you can see what you can work toward with
the items you have.

A highlighted tile comes in two colours:

- **Green** — in logic. The items you have are enough; the randomizer promises this one is doable.
- **Yellow** — reachable, but only by a route the randomizer refused to count on: a rare barter, a
  chest in a structure this seed doesn't treat as progression, a Wandering Trader turning up with the
  right offer. Possible right now if the game cooperates, never required of you.

Yellow tiles are what the `glitch_logic` option controls. It is display-only — item placement is
identical either way — so turning it off simply puts the tracker back to green and red.

## What should I know regarding logic?

- You start in your **start dimension** for free (Overworld by default). The other dimensions each
  require their **Dimension Unlock**, *and* a way to actually build and light the portal.
- Building portals is gated on knowledge: lighting a Nether portal expects you to have the means to do
  so, so a Nether start needs both the Overworld unlock and the ability to obtain and light obsidian
  before you can leave.
- The **Stronghold** gates the End (it is the only End portal), and the **Nether Fortress** gates the
  Wither (wither skeleton skulls) and, through blaze rods → eyes of ender, the End. Locking these
  structures therefore gates those bosses.
- Boss prerequisites are respected: if a required mob or its key resource (e.g. Wither Skeleton for
  the Wither; Blaze and Enderman for reaching the End) is locked behind an Entity Unlock, that unlock
  is treated as progression.
- Structures you do **not** lock are reachable as soon as you can reach their dimension.
- **Logic never expects you to get lucky.** A route only counts if you can rely on it, so a rare drop,
  a piggy barter, a Wandering Trader or a chest in a structure this seed doesn't treat as progression
  is not something an item will ever be placed behind. Mining, crafting and trading with a villager you
  can settle are. The exception is an item that has no dependable source at all — a wither skeleton
  skull is a 5.5% drop and that *is* how you get one — so its sources are kept rather than leaving it
  unobtainable.
- That is why some checks the tracker paints **yellow** look perfectly ordinary: they are reachable,
  just not by a route the randomizer was willing to promise.

## Is there anything else I should know?

- **Death Link** can be enabled from your YAML and toggled in-game from the connection screen.
- The **Structure Finder** is a progressive on-screen locator bar — each copy reveals more of the
  structures around you.
- The **Biome Finder** is a soulbound compass: right-click it, pick a biome in your current dimension,
  and its needle points to the nearest one.
- Two-way chat is bridged between your Minecraft world and the Archipelago server.
- If the connection to the Archipelago server is lost, you are returned to the main title screen.

## A note on AI usage

We want to be upfront about how this project is built. AEMinecraft currently relies **heavily on
AI to write its code**. We simply don't have the time a repository like this normally needs to
maintain, but we care about it and want to keep improving it — especially in the first few releases of
the apworld, where a lot still needs to change.

AI lets us keep that pace. Beyond writing code, we also lean on AI for **design and conception
questions** — weighing options, sanity-checking logic, and shaping features — and we intend to keep
doing so going forward.

If you run into a bug or something that feels off, please don't take it as a lack of care. Bug reports
and suggestions are very welcome and genuinely help us steer the project.
