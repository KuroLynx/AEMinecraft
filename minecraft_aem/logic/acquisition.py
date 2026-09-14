import json
import re
from importlib.resources import files

# AST primitives must be imported directly: `from .. import *` cannot supply them because the
# package __init__ imports this module (via set_rules) before it defines Const/Has/and_/… .
from BaseClasses import ItemClassification

from .ast import And, Const, Has, ReachRegion, ReachLocation, and_, or_, at_least
from ..content.registry import base_pack, overlay_packs  # registry only imports constants → cycle-safe
from .. import *

# Wood-family items (planks / logs / wood / stems / hyphae, stripped or not) have no knowledge or
# material gate — they are free once their dimension is reached. Collapsing them to a bare region
# node instead of recursing through every plank variant keeps acquisition trees small (a recipe that
# takes "#planks" otherwise fans out into a dozen wood subtrees).
_WOOD_RE = re.compile(r"^(stripped_)?[a-z]+_(planks|log|wood|stem|hyphae)$")
_NETHER_WOODS = ("crimson", "warped")

# Item -> required Progressive Material Handling tier (inverted from data.MATERIAL_HANDLING_ITEMS):
# the gate for mining a material, and the last-resort fallback for a material item with no recipe.
_MATERIAL_TIER_BY_ITEM: dict[str, int] = {
    item: tier for tier, items in MATERIAL_HANDLING_ITEMS.items() for item in items
}

# 'gameplay' loot tables (tools/build_acquisition.py) that are really a single mob's reliable,
# renewable output — a gift / interaction / growth table. Grounded in the 26.1.2 jar loot-table
# types: gift tables ARE reliable (an armadillo sheds scute, a chicken lays eggs, a sniffer digs
# seeds, a cat brings a morning gift), so they gate on reaching that mob rather than dropping the
# requirement.
_GAMEPLAY_MOB: dict[str, str] = {
    "armadillo": E_ARMADILLO, "armadillo_shed": E_ARMADILLO,
    "chicken_lay": E_CHICKEN, "turtle_grow": E_TURTLE, "panda_sneeze": E_PANDA,
    "sniffer_digging": E_SNIFFER, "cat_morning_gift": E_CAT,
}

# Mob heads/skulls drop ONLY when a CHARGED creeper kills that mob — verified against the 26.1.2 jar
# (data/minecraft/loot_table/charged_creeper/<mob>.json, dispatched from charged_creeper/root.json;
# none of these heads have a direct mob drop). So the gate is "charge a creeper" — a creeper plus an
# Overworld thunderstorm — AND reach the victim, not merely reaching the victim. (Note:
# wither_skeleton_skull ALSO drops directly from a Wither Skeleton, so its `drops` path keeps the
# simpler entity gate and absorption collapses the redundant charged-creeper AND.)
_CHARGED_CREEPER_VICTIM: dict[str, str] = {
    "creeper": E_CREEPER, "skeleton": E_SKELETON, "zombie": E_ZOMBIE,
    "wither_skeleton": E_WITHER_SKELETON, "piglin": E_PIGLIN,
}

# Villager 'Hero of the Village' profession gift tables: a villager of that profession throws these
# after the player wins a raid, so they gate on winning one (a pillager + a village).
_GAMEPLAY_VILLAGER_GIFTS = frozenset({
    "armorer_gift", "baby_gift", "butcher_gift", "cartographer_gift", "cleric_gift", "farmer_gift",
    "fisherman_gift", "fletcher_gift", "leatherworker_gift", "librarian_gift", "mason_gift",
    "shepherd_gift", "toolsmith_gift", "unemployed_gift", "weaponsmith_gift",
})

# Block-harvest 'gameplay' tables: pick/shear a block for its yield. (region, needs_shears) — the
# block's dimension, plus shears when the table is a shear interaction (honeycomb, pumpkin seeds).
_GAMEPLAY_HARVEST: dict[str, tuple] = {
    "beehive": (REGION_OVERWORLD, True), "pumpkin": (REGION_OVERWORLD, True),
    "cave_vine": (REGION_OVERWORLD, False), "sweet_berry_bush": (REGION_OVERWORLD, False),
}

# Lazily-loaded acquisition table (tools/build_acquisition.py) + reverse id lookups. Cached because
# they are read once per generation but queried thousands of times by the trigger compiler.
_MC_ROOT = __package__.rsplit(".", 1)[0]  # e.g. "worlds.minecraft_aem"
_ACQUISITION: dict | None = None
_ENTITY_BY_GID: dict | None = None


def _acquisition_table() -> dict:
    global _ACQUISITION
    if _ACQUISITION is None:
        table = _load_pack_acquisition(base_pack())
        # Overlay packs (BACAP) contribute ONLY their `advancements` reward source onto the base
        # table (item -> advancement game_ids that grant it). Other overlay sources are intentionally
        # not merged yet (see registry.overlay_packs / the items-merge TODO). The advancements source
        # is gated per seed by the bacap_rewards option in reward_sources, so merging it into
        # the once-cached, option-independent table is safe — an unused source for seeds with the
        # rewards (or the pack) off.
        for pack_dir in overlay_packs().values():
            for item, record in _load_pack_acquisition(pack_dir).items():
                advancements = record.get("advancements")
                if not advancements:
                    continue
                base_record = table.setdefault(item, {})
                base_record["advancements"] = sorted(
                    set(base_record.get("advancements", ())) | set(advancements))
        _ACQUISITION = table
    return _ACQUISITION


def _load_pack_acquisition(pack_dir_name: str) -> dict:
    """A pack's acquisition.json (``{}`` if it has none)."""
    path = files(_MC_ROOT).joinpath("packs", pack_dir_name, "acquisition.json")
    if not path.is_file():
        return {}
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def reward_sources(world) -> dict[str, list[str]]:
    """Item base -> the active location names whose completion grants it as a BACAP advancement
    reward. Empty unless the pack AND its rewards are on (bacap_rewards), mirroring the mod disabling
    BACAP rewards on world load — with them off a reward is not a real way to obtain the item.

    Read only by ``_with_reward``, i.e. by the GLITCH graph — strict logic never sources an item
    through a reward. Only advancements that are an active check this seed contribute (an inactive
    tab / challenge_sanity drop is simply absent), so an item nothing active grants is left out."""
    if not (bool(world.options.blazeandcave.value) and bool(world.options.bacap_rewards.value)):
        return {}
    location_by_gid = {
        data.game_id: name for name, data in world._get_active_locations().items() if data.game_id
    }
    events: dict[str, list[str]] = {}
    for base, record in _acquisition_table().items():
        names = sorted({location_by_gid[gid] for gid in record.get("advancements", ())
                        if gid in location_by_gid})
        if names:
            events[base] = names
    return events


def _entity_by_gid() -> dict:
    global _ENTITY_BY_GID
    if _ENTITY_BY_GID is None:
        _ENTITY_BY_GID = {data.game_id: name for name, data in MOBS_ALL.items()}
    return _ENTITY_BY_GID


def _wood_region(base: str) -> str | None:
    """Region a wood-family item (or a stick) is free in, or ``None`` if it is not wood."""
    if base == "stick" or _WOOD_RE.match(base):
        return REGION_NETHER if any(w in base for w in _NETHER_WOODS) else REGION_OVERWORLD
    return None


# Block id substrings that pin a mined block to a dimension (so e.g. nether wart is gated on the
# Nether, not just "having a pickaxe"). Everything else is treated as Overworld.
_NETHER_BLOCK_HINTS = ("nether", "crimson", "warped", "basalt", "blackstone", "soul_",
                       "magma", "glowstone", "ancient_debris", "nylium", "shroomlight", "gilded")
_END_BLOCK_HINTS = ("end_stone", "chorus", "purpur", "dragon_egg")
# Craftable blocks that ALSO generate naturally, so digging one up (its self-drop) is a genuine
# free source — unlike a placed-only crafted block (planks/wool/slime_block), whose self-mining is
# circular. Only blocks that BOTH self-mine and have a recipe need listing (others keep self-mining
# unconditionally). Missing one merely over-gates it to its recipe; wrongly adding a placed-only
# block would under-gate it, so keep this conservative.
# Structures with nothing above ground to see. Exploring finds every other structure eventually —
# a village, a ruined portal, a mansion all sit on the surface — but a stronghold is buried with no
# trace, so "wander until you trip over one" is not a route at all, not even an unreliable one. Read
# by structure_located, which therefore gives these no free glitch pass.
_NO_SURFACE_TRACE = frozenset({S_STRONGHOLD})

_NATURAL_SELF_MINED = frozenset({
    "stone", "cobblestone", "granite", "diorite", "andesite", "tuff", "calcite", "deepslate",
    "cobbled_deepslate", "dripstone_block", "amethyst_block", "sandstone", "red_sandstone",
    "clay", "snow_block", "packed_ice", "blue_ice", "glowstone", "magma_block", "obsidian",
    "mossy_cobblestone", "mud", "packed_mud", "bone_block",
    # Also here for _placed_block_origin rather than for self-mining: both are craftable from what
    # they drop (a melon from 9 slices, a snow block from 4 snowballs) and neither has a recipe-free
    # source in the packs, so without this a jungle melon and a snowy-biome snow layer would read as
    # "someone must have placed that" and cost their own craft — a cycle, which would take
    # melon_slice's and snowball's honest routes with it.
    "melon", "snow",
})
# Blocks that cannot simply be found: they exist only where something put them there, so mining one
# is not a free natural source the way mining gravel is. Not derivable from the packs — the palettes
# in structures.json say which structures a block DECORATES, not whether it also generates in the
# open world — so the gate each one needs is named here.
#   * nether_wart grows on soul sand in Nether Fortresses and Bastion Remnants and nowhere else;
#     mining it read as "be in the Nether", which made 'Basis of Brewing' free and, through it, the
#     whole brewing tree.
#   * a wither rose drops only when a WITHER kills a mob, so it prices the Wither ('Decaying Beauty').
# Keyed by block; the value is what the mining route must additionally require.
#   * a carved pumpkin is a pumpkin carved WITH SHEARS; the ones you can simply find are the
#     decorations in a woodland mansion or a pillager outpost ('Pumpa kungen!').
#   * a wet sponge is in the sponge room of an OCEAN MONUMENT and nowhere else (the elder guardian's
#     own drop is a separate source on the item). Mining it read as "be in the Overworld", which made
#     wet_sponge — and through the furnace, sponge — free.
#   * a decorated pot you can break for its sherds is one somebody assembled; the ones that generate
#     are the trial chambers' (structures.json's palette says so). Mining a pot back for `sherds`
#     skipped both the pot's own recipe and the brushing the sherds really come from.
#   * a copper golem statue is a copper golem that finished oxidizing — no recipe, and it generates
#     nowhere — so the golem is the price ("entity": its gate carries the copper and the pumpkin).
_BLOCK_ONLY_FROM = {
    "nether_wart": ("structures", ("fortress", "bastion_remnant")),
    "wither_rose": ("boss", E_WITHER),
    "carved_pumpkin": ("structures_or_craft",
                       (("mansion", "pillager_outpost"), "minecraft:shears", "minecraft:pumpkin")),
    "wet_sponge": ("structures", (S_OCEAN_MONUMENT,)),
    "decorated_pot": ("structures", (S_TRIAL_CHAMBERS,)),
    "copper_golem_statue": ("entity", E_COPPER_GOLEM),
}
# Blocks that are the PLACED FORM of the item they drop, under a different name — so mining one back
# is circular the way mining a potted plant is, and reading it as a natural source makes the item
# free. Tripwire is string laid on the ground; that free Overworld path then absorbed string's real
# spider/loot gates in _unique_or, which is what kept 'Spider Smasher' (and 'The Ritual Begins',
# whose black candle needs string) costing nothing even once cobweb asked for a sword. The item's
# own structure routes still stand — string is in plenty of chests.
_PLACED_FORM_BLOCKS = frozenset({"tripwire"})
# Farmland crops. Not one of these blocks generates in the open world: a crop is where somebody
# PLANTED a seed — the player, or a village farmer inside a village's own farm — so mining one back
# is as circular as mining a placed slime block, and the bare `access_region(Overworld)` it compiled
# to let _unique_or absorption delete the item's real structure/mob gates (beetroot had no other
# source at all, so it was simply free; potato and carrot lost their village/husk gates). The honest
# price of a crop is the SEED you plant, which has its own sources — so that is what the mining route
# asks for here. Keyed by crop block -> the item planted to grow it. A crop whose seed IS the item
# being acquired (potato, carrot) resolves to the acquisition cycle it really is, and acquire() drops
# the route, leaving the item's chest/mob sources to carry it.
_PLANTED_CROPS = {
    "wheat": "minecraft:wheat_seeds",
    "carrots": "minecraft:carrot",
    "potatoes": "minecraft:potato",
    "beetroots": "minecraft:beetroot_seeds",
    "melon_stem": "minecraft:melon_seeds",
    "pumpkin_stem": "minecraft:pumpkin_seeds",
    "torchflower_crop": "minecraft:torchflower_seeds",
    "pitcher_crop": "minecraft:pitcher_pod",
    # The grown plants themselves: the crop's last stage is a block of its own, and mining THAT was
    # the free path that kept torchflower and pitcher plant (and with them the sniffer's whole point)
    # costing nothing.
    "torchflower": "minecraft:torchflower_seeds",
    "pitcher_plant": "minecraft:pitcher_pod",
}
# Copper ages where it stands. An `exposed_/weathered_/oxidized_` block is the plain one after it
# weathered, so mining one back is circular — but nothing in the dump says so: you don't CRAFT an
# aged block (you wait), so it has no "recipes" key, `placed_only` read False, and every aged copper
# item compiled to a bare Overworld region. Exposed copper bars cost nothing while plain ones cost a
# copper ingot. The aged block's real price is the plain item plus time; the structures that generate
# copper already aged still come through the palette route in _block_origin_node.
_AGING_PREFIXES = ("exposed_", "weathered_", "oxidized_")
# Tools a block demands for a drop that its loot table does not state, because the demand is the
# block's HARDNESS rather than a match_tool condition. Cobweb is the case: the table hands out string
# to anything that is not shears, but cobweb takes so long to break by hand that a sword is the only
# realistic way through — and without it 'Spider Smasher' cost nothing, taking 'The Ritual Begins'
# (a black candle needs string) with it. Keyed by (block, dropped item).
_EXTRA_DROP_KNOWLEDGE = {
    ("cobweb", "string"): K_SWORD,
}
# Blocks the jar places in CODE rather than in a structure's template palette, so build_structures
# cannot see them: a dried ghast generates in the Nether fossils of a soul sand valley, but the only
# mentions of it anywhere in the jar data are its own loot table and piglin bartering. Merged into
# _block_structures so the block keeps that route — this widens logic (one more way in), it does not
# gate anything.
_EXTRA_BLOCK_STRUCTURES = {
    "dried_ghast": ("nether_fossil",),
}
# What has to be standing in front of you before an empty bucket becomes a FULL one. Filling a bucket
# is a use-interaction, which appears in no recipe, loot table or trade, so the dump has no record of
# the act at all — see the filled-bucket branch in _acquire_compute for what that cost. Keyed by the
# filled item -> the mobs that can fill it (OR: any one of them will do). A bucket filled from the
# world rather than from a mob — water, lava, powder snow — is absent, and needs only the bucket.
_BUCKET_CONTENT_MOBS: dict[str, tuple[str, ...]] = {
    "axolotl_bucket": (E_AXOLOTL,),
    "tadpole_bucket": (E_TADPOLE,),
    "cod_bucket": (E_COD,),
    "salmon_bucket": (E_SALMON,),
    "pufferfish_bucket": (E_PUFFERFISH,),
    "tropical_fish_bucket": (E_TROPICAL_FISH,),
    "milk_bucket": (E_COW, E_MOOSHROOM, E_GOAT),  # any of the three gives milk
}
# needs_<tier>_tool tag -> the material tier the mining pickaxe (and the player) must have reached.
_NEEDS_TIER = {"stone": MAT_STONE, "iron": MAT_IRON, "diamond": MAT_DIAMOND}
_BLOCK_MINING: dict | None = None


def _block_mining() -> dict:
    global _BLOCK_MINING
    if _BLOCK_MINING is None:
        path = files(_MC_ROOT).joinpath("packs", base_pack(), "block_mining.json")
        with path.open(encoding="utf-8") as handle:
            _BLOCK_MINING = json.load(handle)
    return _BLOCK_MINING


_BLOCK_STRUCTURES: dict | None = None
_ITEM_TAGS: dict | None = None


def _item_tag(tag: str) -> frozenset:
    """Members of a vanilla item tag (tags.json, already expanded), as bare paths."""
    global _ITEM_TAGS
    if _ITEM_TAGS is None:
        with files(_MC_ROOT).joinpath("packs", base_pack(), "tags.json").open(encoding="utf-8") as handle:
            _ITEM_TAGS = json.load(handle).get("item", {})
    return frozenset(member.split(":", 1)[-1] for member in _ITEM_TAGS.get(tag, ()))


# Sherds that come out of a structure's own decorated pots and no loot table at all — the 26.1.2 jar
# names flow/guster/scrape only inside trial_chambers structure templates. A pot drops its sherds only
# when broken 'cracked', which data/minecraft/loot_table/blocks/decorated_pot.json ties to breaking it
# with an item in #breaks_decorated_pots; broken otherwise it drops itself, sherds sealed in.
_STRUCTURE_POT_SHERDS: dict[str, str] = {
    "flow_pottery_sherd": S_TRIAL_CHAMBERS,
    "guster_pottery_sherd": S_TRIAL_CHAMBERS,
    "scrape_pottery_sherd": S_TRIAL_CHAMBERS,
}


def _aged_source(block: str) -> str | None:
    """The plain item an aged copper block weathered FROM (``exposed_copper_bars`` ->
    ``minecraft:copper_bars``), or ``None`` when the block is not an aged form of something the
    acquisition table knows. See ``_AGING_PREFIXES``."""
    for prefix in _AGING_PREFIXES:
        if block.startswith(prefix):
            plain = block[len(prefix):]
            if plain in _acquisition_table():
                return f"minecraft:{plain}"
    return None


def _block_structures() -> dict:
    """Reverse of each structure's natural-generation palette (structures.json -> STRUCTURES): block
    id -> the structures it generates in. Lets acquire() treat 'mine this block where it spawns in a
    structure' as a source for a placed-only block (e.g. a comparator in an Ancient City) that
    recipes/loot tables miss."""
    global _BLOCK_STRUCTURES
    if _BLOCK_STRUCTURES is None:
        mapping: dict[str, list] = {}
        for struct_name, data in STRUCTURES.items():
            for block in data.blocks:
                mapping.setdefault(block, []).append(struct_name)
        for block, extra in _EXTRA_BLOCK_STRUCTURES.items():
            known = mapping.setdefault(block, [])
            known += [name for name in extra if name in STRUCTURES and name not in known]
        _BLOCK_STRUCTURES = mapping
    return _BLOCK_STRUCTURES


def _block_region(block: str) -> str:
    if any(hint in block for hint in _END_BLOCK_HINTS):
        return REGION_END
    if any(hint in block for hint in _NETHER_BLOCK_HINTS):
        return REGION_NETHER
    return REGION_OVERWORLD


# Saplings whose tree grows in exactly one biome, so obtaining one IS finding that biome. Oak,
# spruce and birch are left out on purpose: they are spread across so many common biomes that
# nobody has to go looking, and gating them would put the Biome Finder in front of the whole tree.
_BIOME_SAPLINGS = frozenset({
    "jungle_sapling",       # jungle
    "acacia_sapling",       # savanna
    "dark_oak_sapling",     # dark forest
    "cherry_sapling",       # cherry grove
    "pale_oak_sapling",     # pale garden
    "mangrove_propagule",   # mangrove swamp
    "azalea", "flowering_azalea",  # lush caves
})

# The Pale Garden's exclusive blocks. The biome generates nowhere else and nothing here has another
# source, so obtaining any of them IS finding it.
_PALE_GARDEN_BLOCKS = frozenset({
    "pale_oak_log", "pale_oak_wood", "pale_oak_leaves", "pale_oak_planks",
    "pale_moss_block", "pale_moss_carpet", "pale_hanging_moss",
    "creaking_heart", "resin_clump", "resin_block", "resin_brick",
})


class RuleHelper:
    """Builds logic rules as serializable AST nodes (see ``ast.py``).

    Every method returns a ``Rule`` node that is both callable against an AP
    ``CollectionState`` (so it can be handed to ``set_rule``) and serializable for
    export to the mod. All option-dependent branching is resolved here, at build
    time, so the resulting tree contains only the primitive node kinds.
    """

    # A source you can't count on: worse than three attempts in ten. Note this cut sits just above a
    # dense cluster of exactly-0.25 sources (village/bastion/ruined-portal chest pools, witch drops,
    # villager gifts), so it is deliberately strict — those routes are glitch, not logic. _demote
    # still keeps any of them that is an item's only source, or its only one in the start dimension.
    _GLITCH_CHANCE = 0.30

    def __init__(self, world: World, glitch: bool = False):
        self.world = world
        self.player = world.player
        # Structures locked behind a 'Structure Unlock' item (structure_unlock option). Others are
        # gated by their dimension being reachable instead (see self.structure).
        self.locked_structures = world._get_locked_structures()
        # Structures that actually exist this seed (vanilla + overlay packs whose option is on). An
        # inactive overlay structure can't be a source or a reachable target.
        self.active_structures = world._get_active_structures()
        # Options resolved once, up front, so rule nodes never carry option logic.
        self.villager_trust = bool(world.options.villager_trust.value)
        # Knowledge gates active this seed (knowledge_gates option), as BARE names — the form K_* and
        # TOOL_LOCKS use. self.knowledge() drops the requirement for anything not in here.
        self.active_knowledges = world._active_knowledges()
        # Mobs locked behind an 'Entity Unlock' item (mob_spawn_lock option), resolved to concrete
        # mob names (categories/All/individual names all collapse to this set).
        self.locked_mobs = world._get_locked_mobs()
        # Biome Finder enabled (start or in_pool); disabled == 0. Biome-specific advancements require
        # it when on, since that's how you locate the biome.
        self.biome_finder_enabled = bool(world.options.biome_finder.value)
        # Structure Finder enabled (start or in_pool). Read by structure_located: with the option
        # off there is no such item, so the requirement vanishes rather than blocking everything.
        self.structure_finder_enabled = (world.options.structure_finder.value
                                         != world.options.structure_finder.option_disabled)
        # inventory_lock: copies of 'Progressive Inventory Slot' needed before an armor slot exists
        # to wear anything in (see InventoryLock.armor_unlock_items / self.inventory_slots). 0 when
        # armor isn't part of the lock this seed, same "no requirement" shape as self.knowledge.
        self.armor_unlock_items = world.options.inventory_lock.armor_unlock_items
        # BACAP advancement rewards: base item -> the active location names that grant it (empty
        # unless bacap_rewards is on). Consumed by _with_reward, glitch graph only.
        self.reward_sources = reward_sources(world)
        # -- glitch partition ------------------------------------------------
        # Two graphs come out of this compiler. STRICT (glitch=False) is what Archipelago fills
        # against: it drops any alternate route that leans on luck or on content the seed doesn't
        # treat as progression, which is what forces Pickaxe Handling / Material Handling into the
        # early spheres instead of letting a lucky chest stand in for them. GLITCH (glitch=True)
        # keeps those routes, and is shipped in slot_data purely so the mod can paint a tile yellow
        # (LogicState.GLITCHABLE) rather than red. Fill never sees the glitch graph.
        self.glitch = bool(glitch)
        # The dimension the player wakes up in — a route that never leaves it is the cheapest thing
        # this seed has, whatever else the rules demand (see _demote).
        self.start_region = (REGION_NETHER
                             if world.options.start_dimension.current_key == "nether"
                             else REGION_OVERWORLD)
        # Structures / mobs whose unlock item is FULL progression this seed. Everything else is
        # skip_balancing (or has no unlock item at all when the lock option is off), i.e. content the
        # seed doesn't consider load-bearing — so a route through it is a glitch candidate.
        self.progression_structures = {
            name for name in self.active_structures
            if world._structure_classification(name) == ItemClassification.progression
        }
        self.progression_mobs = {
            name for name in MOBS_ALL
            if world._mob_classification(name) == ItemClassification.progression
        }
        # item_gate_behavior, route -> is it gated. Strict logic ignores this and assumes every route
        # gated (the defaults), which is the safe direction: a route the seed opened makes the GAME
        # more permissive than logic, never less, so fill can't deadlock on it. The glitch graph does
        # honour it, so a tile the player can genuinely reach — a chest they may now open ungated —
        # reads yellow instead of a flat lie in red.
        self.gate_routes = world._item_gate_routes()
        # Locations this seed actually created. reached() consults it: a rule may only name a
        # location AP knows about, or state.can_reach_location raises KeyError mid-fill.
        self.active_locations = set(world._get_active_locations())
        # Memo for acquire(): the acquisition table + options are fixed for this helper, so
        # acquire(base, stack) is pure. Datapack-scale compilation calls it millions of times for the
        # same (base, stack) pairs (planks/sticks/ingots recur in every recipe); caching collapses
        # that. Returned nodes are shared read-only across rules, which is safe (eval + to_dict only).
        self._acquire_cache: dict[tuple[str, frozenset, frozenset, bool], object] = {}
        # Structures whose "how do you find one" gate is being computed right now (see structure()).
        # It is part of the acquire() cache key because it changes the answer: while resolving the
        # stronghold's gate, ender pearls must not be sourced from a stronghold chest, and that
        # narrower result must not be cached over the ordinary one.
        self._finding_stack: frozenset = frozenset()
        # True while working out what a trade COSTS (see _trade_currency). Like _finding_stack it is
        # part of the acquire() cache key, because it changes the answer: while pricing the emeralds
        # a trade needs, emeralds must not be sourced from a trade, and that narrower result must not
        # be cached over the ordinary one.
        self._pricing_trade: bool = False
        # Thunks (deferred so cross-referencing mobs don't recurse at construction).
        self.structure_bound_mobs = {
            # Overworld — structure-locked
            E_CAT            : lambda: self.any_of(self.any_village(), self.structure(S_SWAMP_HUT)),
            E_ALLAY          : lambda: self.any_of(self.structure(S_PILLAGER_OUTPOST), self.structure(S_MANSION)),
            # A villager is only ever IN a village — nothing spawns one in the open world. Without
            # this, 'Entity Unlock: Villager' alone was the whole price: 'Kill Entity: Villager' (and
            # every rule that just wants a villager nearby) read in logic with all five villages
            # still locked.
            #
            # Curing a zombie villager is the one route that needs no village, and it is deliberately
            # NOT modelled here. Naming it — either rebuilt through acquire() or as
            # reached('Zombie Doctor') — puts a villager back inside its own price: the golden apple
            # and the weakness potion resolve through ingredients that list villager trades, so
            # can_trade_villager leads back to this thunk. As a rule tree that is a location cycle,
            # and AP's can_reach_location has no cycle guard — it recursed until the interpreter's
            # stack gave out, mid-fill (a full-pool reachability check never notices; only a real
            # Generate.py run does). Ignoring the route only makes logic stricter than the game, which
            # is the safe direction.
            E_VILLAGER       : lambda: self.any_village(),
            E_SILVERFISH     : lambda: self.structure(S_STRONGHOLD),
            E_WARDEN         : lambda: self.structure(S_ANCIENT_CITY),
            E_ENDERMITE      : lambda: self.entity(E_ENDERMAN),  # spawns from Ender Pearl throws
            # A sniffer never spawns: every one in the world hatched from an egg you brushed out of
            # a warm ocean ruin. Same gate the egg carries, so anything wanting the mob (feeding a
            # snifflet, planting what it digs up) inherits the brush and the ruin.
            E_SNIFFER        : lambda: self.acquire("minecraft:sniffer_egg"),

            # Ocean Monument
            E_ELDER_GUARDIAN : lambda: self.structure(S_OCEAN_MONUMENT),
            E_GUARDIAN       : lambda: self.structure(S_OCEAN_MONUMENT),

            # Mansion + raid mobs (Mansion direct, or a raid — see can_raid, which requires every
            # raid mob unlocked because one locked raider stalls the whole raid).
            E_EVOKER         : lambda: self.any_of(
                self.structure(S_MANSION),
                self.can_raid(),
            ),
            E_VINDICATOR     : lambda: self.any_of(
                self.structure(S_MANSION),
                self.can_raid(),
            ),
            # A vex is never spawned by the world — an evoker summons it — so it is exactly as
            # reachable as an evoker. Stating it as the evoker inherits both of that mob's routes
            # AND its spawn-lock: with the Evoker locked no evoker exists to summon anything, which
            # a hand-copied "mansion or raid" pair silently got wrong.
            E_VEX            : lambda: self.entity(E_EVOKER),
            E_RAVAGER        : lambda: self.can_raid(),

            # Nether — structure-locked (delegates to canonical advancement)
            E_BLAZE          : lambda: self.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),
            E_WITHER_SKELETON: lambda: self.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),
            E_PIGLIN_BRUTE   : lambda: self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),

            # End — delegates to City at the End advancement (which encodes Dragon kill + End City)
            E_SHULKER        : lambda: self.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),

            # Trial Chambers
            E_BREEZE         : lambda: self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
        }

        # Extra food/item gate required to *tame* a mob, on top of reaching it (see can_tame).
        # Mobs absent from the map are itemless (mount-tamed) and need only the entity itself.
        self.taming_food = {
            E_WOLF           : lambda: self.can_get_bone(),  # bones
            E_CAT            : lambda: self.can_get_raw_fish(),  # raw cod / salmon
            E_NAUTILUS       : lambda: self.entity(E_PUFFERFISH),
            E_ZOMBIE_NAUTILUS: lambda: self.entity(E_PUFFERFISH),
        }
        # Extra food/item gate required to *breed* a mob, on top of reaching it (see can_breed).
        # Mobs absent from the map breed with a food co-located with them (seeds, flowers, nether
        # fungi, jungle bamboo, …) and so need only the entity itself.
        self.breeding_food = {
            E_ALLAY    : lambda: self.can_duplicate_allay(),  # amethyst + jukebox/disc
            E_ARMADILLO: lambda: self.can_get_spider_eye(),  # spider eye
            E_AXOLOTL  : lambda: self.all_of(self.can_craft_bucket(),
                                             self.entity(E_TROPICAL_FISH)
                                             ),  # bucket of tropical fish
            E_CAT      : lambda: self.can_get_raw_fish(),  # raw cod / salmon
            E_OCELOT   : lambda: self.can_get_raw_fish(),  # raw cod / salmon
            E_COW      : lambda: self.can_get_wheat(),  # wheat
            E_MOOSHROOM: lambda: self.can_get_wheat(),  # wheat
            E_SHEEP    : lambda: self.can_get_wheat(),  # wheat
            E_GOAT     : lambda: self.can_get_wheat(),  # wheat
            E_LLAMA    : lambda: self.can_get_wheat(),  # hay bale = 9 wheat
            E_HORSE    : lambda: self.can_get_golden_food(),  # golden carrot / apple
            E_DONKEY   : lambda: self.can_get_golden_food(),  # golden carrot / apple
            E_PIG      : lambda: self.can_get_pig_food(),  # carrot / potato / beetroot
            E_FROG     : lambda: self.can_get_slimeball(),  # slimeball
            E_TURTLE   : lambda: self.can_get_seagrass(),  # seagrass (shears)
            E_WOLF     : lambda: self.can_get_meat(),  # any meat
            E_NAUTILUS : lambda: self.can_get_all_fish(),
        }
        # Mobs that never spawn naturally and only come from another mob: a breeding cross, a
        # transformation, or a companion spawn. Gated by reaching (and, for the bred ones, being
        # able to breed) their parent(s), on top of reaching their own region. No entry references
        # itself, so there is no rule-build recursion.
        self.parent_bound_mobs = {
            E_MULE           : lambda: self.all_of(self.can_breed(E_HORSE), self.can_breed(E_DONKEY)),  # Horse × Donkey
            E_TADPOLE        : lambda: self.can_breed(E_FROG),                # Frog spawn
            E_TRADER_LLAMA   : lambda: self.entity(E_WANDERING_TRADER),       # spawns leashed to a Wandering Trader
            E_ZOMBIE_NAUTILUS: lambda: self.entity(E_DROWNED),                # drowned-converted variant
            E_ZOGLIN         : lambda: self.entity(E_HOGLIN),                 # a Hoglin that left the Nether
        }
        # Player-constructed mobs: gated by their build materials (snow / copper / iron blocks) +
        # a carved pumpkin, on top of reaching their region (see entity). The material helpers are
        # called with their golem-drop branch disabled, since that branch references the very golem
        # being built → infinite recursion at rule-build time.
        # Pure build recipes (N blocks + a carved pumpkin) for golems you summon by placing blocks.
        # summon() uses these directly for minecraft:summoned_entity (e.g. "Hired Help"): a *naturally
        # spawned* village Iron Golem does NOT count toward summoning, so the recipe must exclude it.
        self.summon_recipes = {
            E_SNOW_GOLEM  : lambda: self.all_of(
                self.can_get_snowball(include_snow_golem=False),  # → snow blocks
                self.can_get_carved_pumpkin(),
            ),
            E_COPPER_GOLEM: lambda: self.all_of(
                self.can_get_copper(include_copper_golem=False),  # → copper block
                self.can_get_carved_pumpkin(),
            ),
            E_IRON_GOLEM  : lambda: self.all_of(  # iron blocks + carved pumpkin
                self.can_get_iron(include_iron_golem=False),
                self.can_get_carved_pumpkin(),
            ),
            # The Wither is built like a golem — 4 soul sand + 3 wither skeleton skulls — and both
            # halves are Nether-only, the skulls specifically a Nether fortress drop. It was missing
            # here, so summon() fell through to entity(); entities.json lists the Wither's region as
            # Overworld (it is built wherever you stand) and it has no structure/parent/biome gate,
            # which left "Withering Heights" as a bare Overworld check — green on a fresh world.
            E_WITHER      : lambda: self.all_of(
                self.acquire("minecraft:wither_skeleton_skull"),
                self.acquire("minecraft:soul_sand"),
            ),
            # Respawning the dragon ("The End... Again...") means placing four End Crystals on the
            # exit portal — which only exists once the first dragon is dead. summon() had no recipe
            # for it and fell through to entity(), i.e. "be in the End".
            E_ENDER_DRAGON: lambda: self.all_of(
                self.outer_end(),
                self.acquire("minecraft:end_crystal"),
            ),
        }
        # How entity() *reaches* each constructed mob: the build recipe, plus any natural spawn. Snow
        # and Copper Golems never spawn naturally; an Iron Golem also spawns in villages, so for the
        # encounter/kill gate either path suffices.
        self.constructed_mobs = {
            E_SNOW_GOLEM  : self.summon_recipes[E_SNOW_GOLEM],
            E_COPPER_GOLEM: self.summon_recipes[E_COPPER_GOLEM],
            E_IRON_GOLEM  : lambda: self.any_of(
                self.any_village(),                   # natural village spawn
                self.summon_recipes[E_IRON_GOLEM](),  # or built
            ),
            # No natural spawn at all: if you did not build it, there is no Wither to meet.
            E_WITHER      : self.summon_recipes[E_WITHER],
        }
        # A PREREQUISITE on top of locating one: something that must have happened before the
        # structure is reachable at all. Thunks, deferred like the mob maps so the acquire()
        # recursion happens at rule-build time rather than during construction.
        self.structure_prerequisites = {
            # An End City is on the outer islands, and the only way out there is an End gateway —
            # which does not exist until the dragon is dead. Reaching the End was the whole gate, so
            # the City, the elytra, the dragon head and the shulker were all free the moment you
            # stepped through the portal.
            S_END_CITY: lambda: self.outer_end(),
        }
        # A structure-specific way to LOCATE one, dependable enough that strict logic accepts it
        # instead of the Finder. The stronghold's is the game's own answer: throw an Eye of Ender
        # and follow where it goes. (The eyes are ALSO needed for the portal, but that is a separate
        # requirement on the Overworld→End edge — being able to find the building is not the same as
        # being able to open the gate in it.)
        self.structure_locate_routes = {
            S_STRONGHOLD: lambda: self.acquire("minecraft:ender_eye"),
        }
        # Mobs whose only natural spawn is a specific, searchable biome — gated on the Biome Finder
        # (when enabled), since that's how you locate the biome.
        #
        # DERIVED, not curated: data.BIOME_BOUND_MOBS is every mob whose spawn biomes all sit in
        # RARE_BIOMES, read off the dump's `biomes` field. The judgement lives in that biome list,
        # where it belongs — this used to be five hand-written lambdas, and the mooshroom was simply
        # missing from them, so 'Super Mooshroom' asked for no Mushroom Fields while its own parent
        # advancement did.
        self.biome_bound_mobs = {name: (lambda: self.needs_biome_finder())
                                 for name in BIOME_BOUND_MOBS}
        # …plus the three the spawn lists cannot speak for, because what they cost is not a search:
        self.biome_bound_mobs.update({
            # Frogs spawn in ordinary swamps, so the derivation rightly leaves them alone — but a frog
            # is only interesting for its three CLIMATE variants (the three froglights), and those
            # really are three journeys.
            E_FROG       : lambda: self.needs_biome_finder(),
            # No spawner entry at all: a creaking hatches from a creaking heart, which generates only
            # in the Pale Garden.
            E_CREAKING   : lambda: self.needs_biome_finder(),
            # Likewise none: a happy ghast comes from a dried ghast, which is Soul Sand Valley — or
            # Piglin bartering, so the finder is only needed without that path.
            E_HAPPY_GHAST: lambda: self.any_of(
                self.can_barter(),
                self.needs_biome_finder(),
            ),
        })
        if not BIOME_BOUND_MOBS:
            # A content pack dumped before `biomes` existed says nothing about spawn biomes, and
            # silence must not read as "gate nothing" — that would quietly loosen every one of these.
            for name in (E_AXOLOTL, E_GOAT, E_MOOSHROOM):
                self.biome_bound_mobs.setdefault(name, lambda: self.needs_biome_finder())

    # -----------------------------------------------------------------------
    # Global
    # -----------------------------------------------------------------------
    def has(self, item: str, count: int = 1):
        return Has(self.player, item, count)

    def has_all(self, *items: str):
        return and_(*[Has(self.player, item) for item in items])

    def has_any(self, *items: str):
        return or_(*[Has(self.player, item) for item in items])

    def any_of(self, *conditions):
        return or_(*conditions)

    def all_of(self, *conditions):
        return and_(*conditions)

    # -----------------------------------------------------------------------
    # Structures
    # -----------------------------------------------------------------------
    def structure(self, struct_gid: str):
        if struct_gid not in STRUCTURES:
            print(f"Warning: {struct_gid} not found !")
            return Const(False)
        if struct_gid not in self.active_structures:
            return Const(False)  # an overlay structure whose pack is off this seed never generates
        # A structure is reachable only once its dimension is reachable (Overworld is always
        # reachable, Nether/End need their access). Locked structures additionally require their
        # unlock item — but the dimension gate still applies, so e.g. the Nether ruined portal is
        # not reachable from the Overworld just because its unlock item was received.
        region = self.access_region(STRUCTURES[struct_gid].region)
        # A prerequisite and/or a structure-specific locate route can both recurse through acquire(),
        # so they run under the finding guard; everything else needs no guard and must not pay the
        # acquire-cache cost of one.
        prereq_thunk = self.structure_prerequisites.get(struct_gid)
        guarded = prereq_thunk is not None or struct_gid in self.structure_locate_routes
        if guarded and struct_gid in self._finding_stack:
            # Reached while working out how to FIND this very structure: its own loot cannot be what
            # leads you to it. A stronghold chest holds Ender Pearls, so acquire("ender_eye") walks
            # straight back here — cut the path (the Enderman drop is the route that survives).
            return Const(False)
        outer = self._finding_stack
        if guarded:
            self._finding_stack = outer | {struct_gid}
        try:
            prereq = prereq_thunk() if prereq_thunk is not None else Const(True)
            located = self.structure_located(struct_gid)
        finally:
            self._finding_stack = outer
        if struct_gid in self.locked_structures:
            return self.all_of(self.has(f"{STRUCT_UNLOCK_PREFIX}{STRUCTURES[struct_gid].label}"),
                               region, prereq, located)
        return self.all_of(region, prereq, located)

    def any_village(self):
        return self.any_of(*[self.structure(gid) for gid in
                             (S_VILLAGE_DESERT, S_VILLAGE_PLAINS, S_VILLAGE_SAVANNA, S_VILLAGE_SNOWY, S_VILLAGE_TAIGA)])

    def mob_unlocked(self, entity_name: str):
        """Just the spawn-lock gate for a mob — 'its unlock item is held' — with none of the
        reachability ``entity()`` also demands.

        This is deliberately a bare has-leaf. Anything that must ask 'can this mob spawn at all?'
        about a mob whose own spawn route runs through the very thing being gated has to use this,
        or the rule recurses: a ravager's only route IS a raid, so a raid asking for ``entity()`` of
        its own raiders would never terminate."""
        if entity_name not in self.locked_mobs:
            return Const(True)
        return self.has(f"{ENTITY_UNLOCK_PREFIX}{entity_name}")

    def can_raid(self):
        """A raid can happen at all: a pillager to take Bad Omen from, a village to carry it into,
        and every raid mob unlocked.

        That last clause is the mob-lock talking. A raid wave spawns each ``MOBS_RAID`` type and only
        clears once its raiders are dead, so one locked member stalls the raid forever — the mod
        therefore refuses to convert Bad Omen into Raid Omen until all of them are unlocked
        (MobSpawnLockService#isAnyRaidMobLocked). Logic has to ask for the same thing or it would
        promise raids the game will not start.

        The raiders are required via mob_unlocked (a has-leaf), NOT entity(): evoker, vindicator and
        ravager all list the raid itself as a spawn route, so entity() here would recurse."""
        return self.all_of(
            self.entity(E_PILLAGER),
            self.any_village(),
            *[self.mob_unlocked(mob) for mob in MOBS_RAID],
        )

    def can_win_raid(self):
        """Win a raid, which is what makes villagers throw profession gifts.

        Stated as what the game actually asks for — see can_raid — NOT as reaching 'Advancement:
        Hero of the Village'. The advancement is only the game's acknowledgement that you did this;
        it is not a prerequisite, and whether it exists as a location at all depends on
        challenge_sanity (it is challenge-framed). Naming it made three gift routes vanish, or worse
        crash the fill with a KeyError, on every seed that left challenge_sanity off — see reached().

        This is the condition ``_gameplay_table`` already used for the fifteen ``*_gift`` loot tables;
        the three hand-written gift routes now share it instead of expressing the same idea a second,
        more fragile way.
        """
        return self.can_raid()

    def any_portal(self, nether_allowed: bool = False):
        portals = [
            S_RUINED_PORTAL, S_RUINED_PORTAL_DESERT, S_RUINED_PORTAL_OCEAN, S_RUINED_PORTAL_MOUNTAIN, S_RUINED_PORTAL_JUNGLE,
            S_RUINED_PORTAL_SWAMP
        ]
        if nether_allowed:
            portals = portals + [S_RUINED_PORTAL_NETHER]
        return self.any_of(*[self.structure(p) for p in portals])

    def any_mineshaft(self):
        return self.any_of(self.structure(S_MINESHAFT), self.structure(S_MINESHAFT_MESA))

    def any_shipwreck(self):
        return self.any_of(self.structure(S_SHIPWRECK), self.structure(S_SHIPWRECK_BEACHED))

    # -----------------------------------------------------------------------
    # Locations
    # -----------------------------------------------------------------------
    def strict_only(self, node):
        """A requirement STRICT logic insists on but the display graph waives.

        The mirror of ``_demote``, which drops flimsy *sources*; this drops a requirement that has a
        tedious-but-real alternative. Travelling 10k blocks out is the case: an elytra is how anyone
        actually does it, and fill should place items as if it were required, but a player with a
        boat and an afternoon can walk. Waived in the glitch graph, so the tile reads yellow —
        possible now if you are willing, never something fill leans on."""
        return Const(True) if self.glitch else node

    def glitch_only(self, node):
        """The inverse of ``strict_only``: a ROUTE that exists only in the permissive graph.

        ``strict_only`` waives a requirement for display; this offers an extra way in for display.
        Written as ``any_of(dependable, glitch_only(flimsy))``, strict logic sees only the
        dependable route while the tile still colours yellow for someone who knows the trick."""
        return node if self.glitch else Const(False)

    def can_fly(self):
        """Sustained flight: an elytra and the rockets to drive it. ``acquire`` resolves the elytra
        to Knowledge: Flying + an End City (it sits in an item frame, not a loot table)."""
        return self.all_of(self.acquire("minecraft:elytra"),
                           self.acquire("minecraft:firework_rocket"))

    def can_break_bedrock(self):
        """Break through a bedrock layer. Not a mining job at any material tier — the block has no
        breaking time — so it is the piston/TNT trick, and a pearl to get through the hole."""
        return self.all_of(self.acquire("minecraft:piston"),
                           self.acquire("minecraft:tnt"),
                           self.acquire("minecraft:ender_pearl"))

    def access_region(self, region_name: str):
        return ReachRegion(self.player, region_name)

    # The two dimensions joined by a portal. Entering either one from the other is the round trip
    # that `enter_dimension` needs; the End is never a start dimension, so it never appears here.
    _PORTAL_PARTNER = {REGION_OVERWORLD: REGION_NETHER, REGION_NETHER: REGION_OVERWORLD}

    def enter_dimension(self, region_name: str):
        """``changed_dimension`` into ``region_name`` — CHANGING dimension, which is not the same as
        being in one. Spawning somewhere never fires the trigger.

        That distinction only bites on the start dimension, and it made 'We Need to Go Deeper' free
        on a Nether start: the check reduced to "be in the Nether", which is where the player wakes
        up, so both fill and the tracker called it done from turn one. In game it needs the whole
        Overworld round trip — the unlock item and obsidian for the return portal — and then a walk
        back through it. So a start-dimension entry additionally requires reaching the dimension on
        the other side of the portal; coming back needs no second gate, because the portal used to
        leave is still standing."""
        node = self.access_region(region_name)
        if region_name != self.start_region:
            return node
        partner = self._PORTAL_PARTNER.get(region_name)
        return self.all_of(node, self.access_region(partner)) if partner else node

    def reached(self, location: str):
        """Reaching another location. Resolves to Const(False) when this seed never created it.

        Options decide which advancements become checks — challenge_sanity drops every challenge-frame
        one, kill_sanity the mob kills, an inactive pack its whole tab — but a curated rule names its
        source unconditionally. 'Hero of the Village' is the live example: it is challenge-framed, so
        the default seed has no such location, while three villager-gift routes (fletcher, cleric,
        fisherman) still ask for it. AP's can_reach_location looks the name up in a dict and raises
        KeyError, which surfaced as an intermittent generation crash: the enclosing any_of short-
        circuits, so whether fill ever evaluates that branch depends on the seed.

        False, not "keep it", because the location genuinely isn't part of this seed's graph. The
        advancement is still completable in game, so this UNDER-approximates: a route the player could
        take is one logic won't count on. That is the safe direction — a route logic ignores can only
        make fill more conservative, never deadlock it — and matches how the exporter and set_rules
        already skip locations the seed didn't create.
        """
        if location not in self.active_locations:
            return Const(False)
        return ReachLocation(self.player, location)

    # -----------------------------------------------------------------------
    # Items
    # -----------------------------------------------------------------------
    def can_get_obsidian(self):
        # Obtain obsidian (e.g. to build a portal). Every source is region-gated, so this resolves
        # correctly per dimension: from the Nether only the Nether ruined portal / Bastion / Nether
        # Fortress / Piglin barter count — the Overworld paths (mining diamonds for the pickaxe,
        # village chests) are unreachable until the Overworld itself is.
        return self.any_of(
            self.reached(f"{ADVANCEMENT_PREFIX}{A_DIAMONDS}"),    # mine it (diamond pickaxe)
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion Remnant chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),  # Nether Fortress
            self.any_portal(True),                                        # any ruined portal (incl. Nether)
            self.can_barter(),                                            # Piglin bartering
            self.any_village(),                                           # village chest
        )

    def can_craft_bucket(self):
        return self.any_of(
            self.reached(f"{ADVANCEMENT_PREFIX}{A_ACQUIRE_HARDWARE}"),  # Craft it yourself
            self.structure(S_MANSION),
            self.structure(S_DUNGEON),
            self.any_village(),
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # Trial Chambers barrel
        )

    def can_get_totem(self):
        return self.entity(E_EVOKER)

    def can_get_string(self):
        return self.any_of(
            self.has_any_entities(E_SPIDER, E_CAVE_SPIDER, E_CAT, E_STRIDER),  # mob drops
            self.knowledge(K_FISHING),  # fishing junk
            self.can_barter(),  # Piglin bartering
            self.structure(S_DESERT_PYRAMID),  # chest
            self.structure(S_JUNGLE_PYRAMID),  # tripwire trap → string
            self.structure(S_PILLAGER_OUTPOST),  # chest
            self.structure(S_TRAIL_RUINS),  # chest
            self.all_of(
                self.knowledge(K_SWORD),
                self.any_mineshaft(),  # cobweb → string
            ),
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion chests
            self.structure(S_DUNGEON),  # chest
            self.structure(S_MANSION),  # chest
        )

    def can_get_arrow(self):
        return self.any_of(
            self.can_get_feather(),
            self.has_any_entities(E_SKELETON, E_STRAY, E_BOGGED, E_PARCHED),  # Arrow drop
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion Remnant chest
            self.structure(S_PILLAGER_OUTPOST),  # Pillager Outpost chest
            self.structure(S_JUNGLE_PYRAMID),  # Temple Dispenser
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # Entrance/Supply/Common chest | Also tipped arrow
            self.any_village(),  # Fletcher chest
            self.can_trade_villager(),  # Fletcher trade
            self.can_win_raid(),  # Fletcher gift
            self.can_barter(),  # Spectral Arrow
        )

    def can_get_disc(self):
        return self.any_of(
            self.all_of(self.has_any_entities(E_SKELETON, E_STRAY, E_BOGGED, E_PARCHED), self.entity(E_CREEPER)),
            # skeleton variant kills Creeper
            self.entity(E_GHAST),  # Tears disc — deflect fireball
            self.all_of(self.has_brush(), self.structure(S_TRAIL_RUINS)),  # Relic disc — archaeology
            self.structure(S_DUNGEON),  # 13, cat, otherside
            self.structure(S_ANCIENT_CITY),  # 13, cat, otherside
            self.structure(S_MANSION),  # 13, cat
            self.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # otherside — Stronghold
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Pigstep — Bastion
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # Creator (Music Box) — decorated pots
            self.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}"),  # Precipice/Creator — Vault
            self.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),  # Creator — Ominous Vault
            # NOTE: a Chicken Jockey (Baby Zombie riding a Chicken) can drop music_disc_lava_chicken.
            # Not in logic — revisit if mob-lock variants (e.g. Chicken Jockey) are introduced.
        )

    def can_get_spyglass(self):
        # Spyglass = 2 amethyst shards + 1 copper ingot.
        return self.all_of(
            self.can_get_copper(),
            self.any_of(
                self.knowledge(K_PICKAXE),       # mine an amethyst geode
                self.structure(S_ANCIENT_CITY),  # chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # Trial Chambers
            ),
        )

    def can_get_trident(self):
        return self.all_of(
            self.knowledge(K_TRIDENT),
            self.any_of(
                self.entity(E_DROWNED),
                self.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}"),
            )
        )

    def can_get_redstone(self):
        # Redstone has no Nether/End source at all (ore, chests, mobs and trades are all Overworld).
        return self.all_of(
            self.access_region(REGION_OVERWORLD),
            self.any_of(
                self.all_of(
                    self.knowledge(K_PICKAXE),
                    self.material(MAT_IRON),
                ),  # mine Redstone Ore
                self.any_mineshaft(),  # chest
                self.structure(S_DUNGEON),  # chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # Stronghold chest
                self.any_village(),  # temple chest
                self.structure(S_MANSION),  # chest
                self.entity(E_WITCH),  # Witch drop
                self.can_win_raid(),  # Cleric gift
                self.can_trade_villager(1),  # Cleric novice trade (cleric/1/emerald_redstone)
            ),
        )

    def can_get_snowball(self, include_snow_golem: bool = True):
        # ``include_snow_golem`` must be False when building the Snow Golem gate itself, otherwise
        # entity(Snow Golem) → this helper → entity(Snow Golem) recurses at rule-build time.
        # Sources verified against the 26.1.2 jar (acquisition indexer + loot tables): snow layers /
        # snow blocks drop snowballs only when mined with a SHOVEL (block tool gate → K_SHOVEL); the
        # only snowball *chest* tables are the Ancient City ice box, the snowy village house, and the
        # Trial Chambers. Igloos have no snowball table — their snow is just snow-block mining, so it
        # is already covered by K_SHOVEL and must not appear as a shovel-free source.
        sources = [
            self.knowledge(K_SHOVEL),  # dig snow layers / snow blocks (incl. igloo / snowy-village blocks)
            self.structure(S_ANCIENT_CITY),  # Ice Box chest
            self.any_village(),  # Snowy village house chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # chamber chest
        ]
        if include_snow_golem:
            sources.append(self.entity(E_SNOW_GOLEM))  # Snow Golem drop
        return self.any_of(*sources)

    def can_get_carved_pumpkin(self):
        # Carve a wild pumpkin with shears (pumpkins grow freely in the Overworld), or find one
        # already carved and placed in a structure.
        return self.any_of(
            self.can_get_shear(),                # shears + naturally-grown pumpkin
            self.structure(S_PILLAGER_OUTPOST),  # placed in structure
            self.structure(S_MANSION),           # placed in structure
        )

    def can_get_egg(self):
        return self.any_of(
            self.entity(E_CHICKEN),  # Chicken lay
            self.any_village(),  # Fletcher chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # chamber chest
        )

    def can_get_feather(self):
        return self.any_of(
            self.entity(E_CHICKEN),  # Chicken drop
            self.entity(E_PARROT),  # Parrot drop
            self.entity(E_CAT),  # Cat morning gift
            self.any_village(),  # Fletcher/Plains House chest
            self.any_shipwreck(),  # Map chest
        )

    def can_get_gold(self):
        return self.all_of(
            self.material(MAT_GOLD),  # always needed — unlock gold tier
            self.any_of(
                self.knowledge(K_PICKAXE),  # mine Gold Ore
                self.entity(E_ZOMBIFIED_PIGLIN),  # Gold Nugget drop
                self.can_barter(),  # Piglin bartering
                self.any_mineshaft(),  # Gold Ingot in chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion chests
                self.structure(S_DESERT_PYRAMID),  # Gold Ingot in chest
                self.structure(S_JUNGLE_PYRAMID),  # Gold Ingot in chest
                self.structure(S_BURIED_TREASURE),  # Gold Ingot in chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),  # Nether Fortress bridge
                self.any_portal(True),  # Ruined Portal
                self.any_shipwreck(),  # Treasure chest
                self.structure(S_DUNGEON),  # Gold Ingot in chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # Stronghold chest
                self.any_village(),  # Temple/Toolsmith/Weaponsmith
                self.structure(S_MANSION),  # Gold Ingot in chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),  # End City
                self.structure(S_OCEAN_RUIN_COLD),  # Gold Nugget
                self.structure(S_OCEAN_RUIN_WARM),  # Gold Nugget
                self.structure(S_TRAIL_RUINS),  # Gold Nugget
                self.structure(S_IGLOO),  # Gold Nugget
            ),
        )

    def has_brush(self):
        # Brush = copper ingot + feather + stick.
        return self.all_of(
            self.knowledge(K_BRUSH),
            self.can_get_copper(),
            self.can_get_feather(),
        )

    def can_get_copper(self, include_copper_golem: bool = True):
        # ``include_copper_golem`` must be False when building the Copper Golem gate itself,
        # otherwise entity(Copper Golem) → this helper → entity(Copper Golem) recurses.
        sources = [
            self.knowledge(K_PICKAXE),  # mine Copper Ore
            self.entity(E_DROWNED),  # Copper Ingot drop
        ]
        if include_copper_golem:
            sources.append(self.entity(E_COPPER_GOLEM))  # Copper Golem drop
        return self.all_of(
            self.material(MAT_COPPER),  # always needed — unlock copper tier
            self.access_region(REGION_OVERWORLD),  # no copper of any kind in the Nether/End
            self.any_of(*sources),
        )

    def can_get_iron(self, include_iron_golem: bool = True):
        # ``include_iron_golem`` must be False when building the Iron Golem gate itself, otherwise
        # entity(Iron Golem) → this helper → entity(Iron Golem) recurses at rule-build time.
        sources = [
            self.all_of(self.knowledge(K_PICKAXE), self.access_region(REGION_OVERWORLD)),  # mine Iron Ore (Overworld only)
            self.has_any_entities(E_HUSK, E_ZOMBIE, E_ZOMBIE_VILLAGER),  # mob drops
            self.any_mineshaft(),  # chest
            self.structure(S_DESERT_PYRAMID),  # chest
            self.structure(S_JUNGLE_PYRAMID),  # chest
            self.structure(S_PILLAGER_OUTPOST),  # chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion chests
            self.structure(S_BURIED_TREASURE),  # chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),  # Nether Fortress chest
            self.any_shipwreck(),  # Treasure chest
            self.structure(S_DUNGEON),  # chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # Stronghold chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # Trial Chambers chest
            self.any_village(),  # Toolsmith/Weaponsmith/Armorer chest
            self.structure(S_MANSION),  # chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),  # End City chest
        ]
        if include_iron_golem:
            sources.append(self.entity(E_IRON_GOLEM))  # Iron Golem drop
        return self.all_of(
            self.material(MAT_IRON),  # always needed — unlock iron tier
            self.any_of(*sources),
        )

    def can_get_shear(self):
        # Shears = 2 iron ingots (verified against the 26.1.2 jar). They are also sold by a Shepherd
        # (shepherd/1 trade) and found in the snowy-village shepherd house chest, but both of those
        # require reaching a village — which is itself an iron source (see can_get_iron) — so iron
        # access already subsumes them, no extra branch needed. The mod additionally gates shears
        # behind Knowledge: Shear Handling + the iron material tier (data.py TOOL_LOCKS), enforced on
        # craft *and* pickup, so the Knowledge is always required regardless of how shears are got.
        # Iron is taken with the Iron Golem drop disabled: building that golem already needs iron, so
        # it is never a unique iron source here, and leaving it on would recurse through the carved-
        # pumpkin gate (entity(Iron Golem) → carved pumpkin → shears → iron → entity(Iron Golem)).
        return self.all_of(
            self.knowledge(K_SHEAR),
            self.can_get_iron(include_iron_golem=False),
        )

    def can_get_honeycomb(self):
        # Honeycomb (verified against the 26.1.2 jar) comes from exactly two sources: shearing a
        # full beehive/bee-nest (needs shears + bees) or the Trial Chambers corridor/entrance chests.
        return self.any_of(
            self.all_of(
                self.can_get_shear(),
                self.entity(E_BEE),
            ),
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
        )

    def can_get_notch_apple(self):
        return self.any_of(
            self.any_mineshaft(),  # Mineshaft chest
            self.structure(S_ANCIENT_CITY),  # Ancient City chest
            self.structure(S_DESERT_PYRAMID),  # Desert Pyramid chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion Treasure chest
            self.any_portal(True),  # Ruined Portal chest
            self.structure(S_DUNGEON),  # Dungeon chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),  # Ominous Unique Vault
            self.structure(S_MANSION),  # Mansion chest
        )

    def can_get_cake(self):
        return self.any_of(
            # Crafting the cake: 3 milk buckets + 2 sugar + 1 egg + 3 wheat (sugar cane is trivial).
            self.all_of(
                self.entity(E_COW),
                self.can_craft_bucket(),
                self.can_get_egg(),
                self.can_get_wheat(),
            ),
            # Getting it
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
            self.can_trade_villager(4),  # Farmer expert trade (farmer/4/emerald_cake)
        )

    def can_get_bed(self):
        return self.any_of(
            self.entity(E_SHEEP),  # wool from sheep, plus planks → craft
            self.can_get_string(),  # 4 string → 1 wool
            self.any_village(),  # bed in house, shepherd chest
            self.structure(S_MANSION),
            self.structure(S_IGLOO),
            self.any_shipwreck(),  # supply chest
            self.can_trade_villager(2),  # Shepherd sells wool @lvl2 → craft bed (beds direct @lvl3)
        )

    def can_kill(self):
        """Kill a mob that requires a real weapon to fight safely — needs a melee weapon Knowledge
        (sword / axe / spear). Use for hostile/tanky mobs and bosses."""
        return self.any_of(
            self.knowledge(K_SWORD),
            self.knowledge(K_AXE),
            self.knowledge(K_SPEAR),
        )

    def can_breath_underwater(self):
        # Every way to breathe underwater long enough to fight down there (e.g. the Elder Guardian).
        return self.any_of(
            self.all_of(self.knowledge(K_BREWING), self.entity(E_PUFFERFISH)),         # Water Breathing potion
            self.all_of(self.entity(E_NAUTILUS), self.structure(S_BURIED_TREASURE)),   # Conduit: nautilus shells + heart of the sea
            self.entity(E_TURTLE),                                                     # Turtle Shell helmet (scute)
        )

    def can_kill_any_mob(self):
        # At least one non-boss mob is reachable (every non-boss mob is beatable bare-handed). Used
        # where the action is just "kill something", e.g. spreading sculk.
        return self.any_of(*[
            self.entity(name) for name, data in MOBS_ALL.items()
            if data.category != MCEntityCategory.BOSS
        ])

    def outer_end(self):
        """Being able to get to the End's OUTER islands — i.e. having beaten the Ender Dragon.

        The End is really two places. The central island is what the portal drops you on: the
        dragon, the exit portal, nothing else. Everything people mean by "the End" — End Cities,
        elytra, shulkers, chorus fruit, the dragon egg, a second dragon — is on the outer islands,
        and the only route there is an End gateway, which spawns when the dragon dies. Modeled as
        the CAPABILITY to kill it (can_defeat) rather than a reference to the kill location, so it
        is well defined whatever the goal is and adds no loc() node to the graph."""
        return self.can_defeat(E_ENDER_DRAGON)

    # Structure Finder tier strict logic will count on. A copy reveals the nearest 5, then 10, then
    # HALF of everything findable, then three quarters, then all (StructureFinderService.cap). The
    # first two are distance-ordered, so whether the structure you want is on the bar is down to
    # your world; from half upward it is dependable. Mirrors MAX_TIER on the mod side.
    _FINDER_TIER_DEPENDABLE = 3

    def structure_located(self, struct_gid: str):
        """How you FIND this structure — the Finder is the route strict logic counts on.

        Wandering until you stumble on a mansion is a real way to play and a terrible thing for a
        randomizer to require, so it is an alternate: kept in the glitch graph, dropped from strict.
        One or two Finder copies sit on the same footing — they show the nearest few structures by
        distance, which may or may not include the one you need. From tier 3 (half of everything
        findable) up it is a promise, so that is what strict asks for.

        Two structures do not follow that shape:

        * A stronghold has NO surface trace, so wandering is not a route at all, not even an
          unreliable one — which is why it gets no free glitch pass. What it gets instead is
          structure_locate_routes: Eyes of Ender find one every time, so that is strict.
        * With the Finder option off no such item exists this seed, and for anything you CAN stumble
          on the requirement vanishes entirely (as needs_biome_finder does for biomes). A stronghold
          still needs its eyes.
        """
        routes = []
        special = self.structure_locate_routes.get(struct_gid)
        if special is not None:
            routes.append(special())
        if self.structure_finder_enabled:
            routes.append(self.has(ITEM_STRUCTURE_FINDER, self._FINDER_TIER_DEPENDABLE))
        explorable = struct_gid not in _NO_SURFACE_TRACE
        if explorable and (self.glitch or not self.structure_finder_enabled):
            routes.append(Const(True))  # you can just go looking
        return self.any_of(*routes) if routes else Const(False)

    def needs_biome_finder(self):
        # Advancements that require finding a specific biome depend on the Biome Finder when it is
        # enabled; with it disabled no such item exists, so the requirement vanishes.
        return self.has(ITEM_BIOME_FINDER) if self.biome_finder_enabled else Const(True)

    # -----------------------------------------------------------------------
    # Breeding / taming foods
    # -----------------------------------------------------------------------
    def can_get_bone(self):
        return self.any_of(
            self.has_any_entities(E_SKELETON, E_STRAY, E_BOGGED, E_PARCHED),  # mob drops
            self.knowledge(K_FISHING),       # fishing junk
            self.structure(S_DESERT_PYRAMID),  # chest
            self.structure(S_JUNGLE_PYRAMID),  # chest
            self.structure(S_DUNGEON),         # chest
            self.structure(S_MANSION),         # chest
            self.structure(S_ANCIENT_CITY),    # chest
        )

    def can_get_raw_fish(self):
        # Raw cod / salmon (cat & ocelot food).
        return self.any_of(
            self.has_any_entities(E_COD, E_SALMON),                 # punch/kill the fish
            self.knowledge(K_FISHING),                              # rod
            self.has_any_entities(E_GUARDIAN, E_ELDER_GUARDIAN, E_DOLPHIN, E_POLAR_BEAR),  # mob drops
            self.any_village(),                                     # village chest
            self.can_win_raid(),  # Fisherman gift
        )

    def can_get_all_fish(self):
        # Any fish item, including the puffer/tropical variants used to breed nautili.
        return self.any_of(
            self.can_get_raw_fish(),
            self.entity(E_PUFFERFISH),
            self.entity(E_TROPICAL_FISH),
        )

    def can_get_wheat(self):
        # Till + harvest needs a hoe; otherwise wheat is found ready-made in many chests.
        return self.any_of(
            self.knowledge(K_HOE),
            self.any_village(),               # farms / chests
            self.any_shipwreck(),             # supply chest
            self.structure(S_PILLAGER_OUTPOST),
            self.structure(S_DUNGEON),
            self.structure(S_IGLOO),
            self.structure(S_TRAIL_RUINS),
            self.structure(S_MANSION),
            self.structure(S_OCEAN_RUIN_COLD),
            self.structure(S_OCEAN_RUIN_WARM),
        )

    def can_get_carrot(self):
        return self.any_of(
            self.any_village(),                 # village farms / chests
            self.structure(S_PILLAGER_OUTPOST),  # chest
            self.any_shipwreck(),               # chest
            self.has_any_entities(E_ZOMBIE, E_HUSK, E_ZOMBIE_VILLAGER),  # rare drop
        )

    def can_get_golden_apple(self):
        # Craft (gold + apple; apples drop freely from oak/dark-oak leaves) or find ready-made.
        return self.any_of(
            self.can_get_gold(),                                            # craft it
            self.any_mineshaft(),                                           # chest
            self.structure(S_DESERT_PYRAMID),                               # chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),   # Bastion chest
            self.structure(S_IGLOO),                                        # chest
            self.any_portal(True),                                          # Ruined Portal chest
            self.structure(S_DUNGEON),                                      # chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),               # Stronghold chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),         # Trial Chambers chest
            self.structure(S_OCEAN_RUIN_COLD),                             # Underwater Ruin chest
            self.structure(S_OCEAN_RUIN_WARM),
            self.structure(S_MANSION),                                      # chest
            self.structure(S_ANCIENT_CITY),                                # placed in city center
        )

    def can_get_golden_carrot(self):
        # Craft (gold nuggets + carrot) or find ready-made.
        return self.any_of(
            self.all_of(self.can_get_gold(), self.can_get_carrot()),       # craft it
            self.structure(S_ANCIENT_CITY),                                # ice box chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),   # Bastion chest
            self.any_portal(True),                                          # Ruined Portal chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),         # Trial Chambers chest
            self.can_trade_villager(5),                                     # Farmer master trade (farmer/5/emerald_golden_carrot)
        )

    def can_get_golden_food(self):
        # Either golden apple or golden carrot (e.g. horse/donkey breeding accepts both).
        return self.any_of(self.can_get_golden_apple(), self.can_get_golden_carrot())

    def can_get_pig_food(self):
        # Carrot / potato / beetroot share the same sources (village farms, chests, zombie drops).
        return self.can_get_carrot()

    def can_get_spider_eye(self):
        return self.any_of(
            self.has_any_entities(E_SPIDER, E_CAVE_SPIDER, E_WITCH),  # drops
            self.structure(S_DESERT_PYRAMID),  # chest
        )

    def can_get_slimeball(self):
        return self.any_of(
            self.entity(E_SLIME),  # Slime drop
            self.can_trade_wandering_trader(),  # a trader sells them — glitch graph only
        )

    def can_get_seagrass(self):
        return self.any_of(
            self.can_get_shear(),                               # shear seagrass
            self.entity(E_TURTLE),  # Turtle drop
        )

    def can_get_meat(self):
        # Wolves accept any meat, including rotten flesh. Any non-boss mob is killable without a
        # weapon, so this reduces to reaching one of these meat/flesh sources.
        return self.has_any_entities(E_COW, E_PIG, E_SHEEP, E_CHICKEN, E_RABBIT, E_ZOMBIE)

    def can_get_food(self):
        # "Obtain any edible item" (e.g. the Husbandry root, which validates on eating anything).
        # The Overworld has trivial food everywhere; the Nether grows none, so a Nether-start player
        # needs a hunted or looted source. Every path below is a real, in-jar food source — adding
        # them only widens reachability, never a fake path that could soft-lock generation.
        return self.any_of(
            self.access_region(REGION_OVERWORLD),                          # apples, crops, animals
            self.can_get_meat(),                                           # cow/pig/sheep/chicken/rabbit/zombie
            self.entity(E_HOGLIN),                                         # raw porkchop (crimson forest / bastion stable)
            self.entity(E_ZOMBIFIED_PIGLIN),                              # rotten flesh (edible)
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion chests: cooked porkchop + golden apple/carrot
            self.can_get_golden_food(),                                   # golden apple/carrot (ruined portal, structure loot, …)
        )

    def can_duplicate_allay(self):
        # Allay duplicate: hand it an Amethyst Shard while it dances to a jukebox.
        return self.all_of(
            self.any_of(
                self.knowledge(K_PICKAXE),       # mine an amethyst geode
                self.structure(S_ANCIENT_CITY),  # chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # Trial Chambers
            ),
            self.material(MAT_DIAMOND),  # jukebox needs a diamond
            self.can_get_disc(),         # disc to play
        )

    # -----------------------------------------------------------------------
    # Entities
    # -----------------------------------------------------------------------
    def has_all_entities(self, *entity_names: str):
        return self.all_of(*[self.entity(name) for name in entity_names])

    def has_any_entities(self, *entity_names: str):
        return self.any_of(*[self.entity(name) for name in entity_names])

    def has_n_entities(self, n: int, *entity_names: str):
        """Reach at least ``n`` distinct mobs from the pool. Uses an AtLeast node so the rule
        stays O(len(pool)) instead of an OR over C(len(pool), n) combinations (which blew the
        serialized export up to ~500MB for Arbalistic's 35-mob pool)."""
        return at_least(n, [self.entity(name) for name in entity_names])

    def entity(self, entity_name: str):
        if entity_name not in MOBS_ALL:
            print(f"Warning: {entity_name} not found !")

        entity_data = MOBS_ALL[entity_name]
        structure_thunk = self.structure_bound_mobs.get(entity_name)
        structure_node = structure_thunk() if structure_thunk is not None else Const(True)
        build_thunk = self.constructed_mobs.get(entity_name)
        build_node = build_thunk() if build_thunk is not None else Const(True)
        parent_thunk = self.parent_bound_mobs.get(entity_name)
        parent_node = parent_thunk() if parent_thunk is not None else Const(True)
        biome_thunk = self.biome_bound_mobs.get(entity_name)
        biome_node = biome_thunk() if biome_thunk is not None else Const(True)
        mob_locked = (entity_name in self.locked_mobs)
        unlock_node = self.has(f"{ENTITY_UNLOCK_PREFIX}{entity_name}") if mob_locked else Const(True)

        return self.all_of(
            self.access_region(entity_data.region),
            structure_node,
            build_node,
            parent_node,
            biome_node,
            unlock_node,
        )

    def summon(self, entity_name: str):
        """Gate for *summoning/building* an entity (minecraft:summoned_entity), e.g. constructing an
        Iron Golem for "Hired Help". Unlike entity(), a naturally spawned mob does not count, so the
        natural-spawn paths are excluded and the build recipe is required. Region access and the
        spawn-lock unlock still apply (a locked mob cannot be built either). Entities with no known
        recipe fall back to plain reachability."""
        recipe = self.summon_recipes.get(entity_name)
        if recipe is None:
            return self.entity(entity_name)
        entity_data = MOBS_ALL[entity_name]
        mob_locked = (entity_name in self.locked_mobs)
        unlock_node = self.has(f"{ENTITY_UNLOCK_PREFIX}{entity_name}") if mob_locked else Const(True)
        return self.all_of(
            self.access_region(entity_data.region),
            recipe(),
            unlock_node,
        )

    def can_defeat(self, entity_name: str):
        """Gate for *killing* a mob — its drops and any kill goal. Ordinary mobs reduce to plain
        reachability (beatable bare-handed via the boat trap); the four bosses require the gear,
        knowledge and environment their fight demands. Single source of truth, shared by the
        entity-kill locations (engine.collect_entity_rules) and boss drops resolved through acquire()
        (e.g. a nether star from the Wither)."""
        if entity_name == E_ENDER_DRAGON:
            return self.all_of(
                self.entity(E_ENDER_DRAGON),
                self.knowledge(K_BOW),  # shoot out the end crystals
                self.any_of(self.can_kill(), self.can_get_bed()),  # melee or bed-bombing
            )
        if entity_name == E_WITHER:
            return self.all_of(
                self.reached(f"{ADVANCEMENT_PREFIX}{A_SPOOKY_SCARY_SKELETON}"),  # wither skulls
                self.entity(E_WITHER),
                self.can_kill(),
                self.knowledge(K_ARMOR),  # survive the blast / wither effect
                self.material(MAT_IRON),  # at least iron-tier gear
            )
        if entity_name == E_WARDEN:
            return self.all_of(self.entity(E_WARDEN), self.can_kill())
        if entity_name == E_ELDER_GUARDIAN:
            return self.all_of(
                self.entity(E_ELDER_GUARDIAN),
                self.can_kill(),
                self.can_breath_underwater(),  # survive the fight underwater
            )
        return self.entity(entity_name)

    def can_tame(self, entity_name: str):
        """Reach the mob *and* hold its taming item (bones, fish, …). Mobs with no taming
        item (mount-tamed: horses, llamas, …) reduce to plain reachability."""
        food_thunk = self.taming_food.get(entity_name)
        food_node = food_thunk() if food_thunk is not None else Const(True)
        return self.all_of(self.entity(entity_name), food_node)

    def can_breed(self, entity_name: str):
        """Reach the mob *and* hold its breeding food. Mobs whose food is co-located with them
        (seeds, flowers, nether fungi, …) reduce to plain reachability."""
        food_thunk = self.breeding_food.get(entity_name)
        food_node = food_thunk() if food_thunk is not None else Const(True)
        return self.all_of(self.entity(entity_name), food_node)

    # -----------------------------------------------------------------------
    # Trades
    # -----------------------------------------------------------------------
    def _trade_currency(self):
        """What a trade costs the player: emeralds.

        Every buy offer takes them, and neither trade helper used to ask for any — which for a
        villager merely made a real gate cheaper, but for the Wandering Trader (no village, no
        profession, no trust tier) left ``can_trade_wandering_trader`` as nothing but "be in the
        Overworld". Anything whose only source was a trader offer — a pufferfish bucket, a bucket of
        tropical fish — was therefore obtainable holding nothing at all, and worse, a free branch in
        an OR lets _unique_or absorption delete the gates beside it (``can_get_slimeball`` is slime
        drop OR trader offer, so a seed locking hostiles lost its Slime gate to the free trader).

        Emeralds are themselves traded, so acquire("emerald") walks back here. In strict logic it
        no longer can: emeralds resolve to can_sell_to_villager, which asks for no currency. The
        ``_pricing_trade`` guard is what holds the glitch graph together, where the emerald branch
        does read the record's own trade routes — inside the guard this returns free rather than
        recursing, because a trade route cannot be what pays for itself."""
        if self._pricing_trade:
            return Const(True)
        self._pricing_trade = True
        try:
            emeralds = self.acquire("minecraft:emerald")
        finally:
            self._pricing_trade = False
        return emeralds if emeralds is not None else Const(True)

    def can_sell_to_villager(self, tier: int = 1):
        """Sell TO a profession villager at trade level ``tier`` — the way emeralds enter a world.

        The same villager, village and trust tier a buy offer needs, minus the currency: a sell
        offer is what *pays* you, so it must not ask for the emeralds it is how you get. Only
        acquire("emerald") wants this shape; everything else trades in the other direction and
        should use ``can_trade_villager``."""
        base = self.all_of(self.entity(E_VILLAGER), self.any_village())
        if self.villager_trust:
            return self.all_of(base, self.has(ITEM_VILLAGER_TRUST, tier))
        return base

    def can_trade_villager(self, tier: int = 1):
        """Trade with a profession villager in a village at trade level ``tier``
        (novice = 1 … master = 5), holding the emeralds it costs. When the
        villager_trust option is on, the level is gated behind that many
        Progressive Villager Trust items."""
        return self.all_of(self.can_sell_to_villager(tier), self._trade_currency())

    def can_trade_wandering_trader(self):
        """Trade with a Wandering Trader — GLITCH GRAPH ONLY. Strict logic never sees this route.

        You cannot make a trader spawn, and you cannot make one roll the offer you want, so it is
        not a route fill may plan around. Being merely *demoted* was not enough: _demote only
        applies to routes that come out of the acquisition table, so a hand-written helper that
        OR-ed this in — can_get_slimeball is slime drop OR trader offer — put the trade straight
        into the strict graph, where _unique_or absorption reads ``A ∨ (A ∧ B)`` as ``A`` and the
        cheaper trade branch deleted the Slime gate beside it. Returning Const(False) in strict mode
        makes that impossible from every call site at once: or_ drops a false branch instead of
        letting it swallow its siblings.

        In the glitch graph it is the real thing: reach one, and hold the emeralds it charges (no
        trade levels, so villager_trust never applies)."""
        if not self.glitch:
            return Const(False)
        return self.all_of(self.entity(E_WANDERING_TRADER), self._trade_currency())

    def meet_wandering_trader(self):
        """Trade with a Wandering Trader when the check itself names one — BOTH graphs.

        can_trade_wandering_trader is glitch-only because a trader is a flimsy *source* of an item:
        which offer it rolls is luck. A criterion that pins the trader has no alternative to it, and
        traders do keep spawning near the player on their own, so strict logic asks for the unlock
        and the emeralds rather than calling the check impossible."""
        return self.all_of(self.entity(E_WANDERING_TRADER), self._trade_currency())

    def take_lock(self, item_id: str, route: str):
        """What the mod demands before ``item_id`` can be TAKEN from a GUI or the floor, not how to
        get it: MaterialLockService's raw-material tier, and a tool lock's Knowledge + tier (a gated
        station/container block's Knowledge counts as one). ``route`` is the item_gate_behavior
        channel the take happens on; an open route asks for nothing."""
        if self.route_open(route):
            return Const(True)
        base = item_id.split(":", 1)[-1]
        parts = []
        tier = _MATERIAL_TIER_BY_ITEM.get(base)
        if tier:
            parts.append(self.has(ITEM_MATERIAL_HANDLING, tier))
        knowledge_name, tool_tier = TOOL_LOCKS.get(base, (BLOCK_KNOWLEDGE.get(f"minecraft:{base}"), 0))
        if knowledge_name is not None and knowledge_name in self.active_knowledges:
            parts.append(self.knowledge(knowledge_name))
            if tool_tier > 0:
                parts.append(self.has(ITEM_MATERIAL_HANDLING, tool_tier))
        return self.all_of(*parts)

    def can_trade(self):
        """The player can perform *some* trade — a novice (min-level) villager or a
        Wandering Trader. Use for rules that only need "a trade happened" with no
        specific profession/level (e.g. What a Deal!, Star Trader)."""
        return self.any_of(self.can_trade_villager(), self.can_trade_wandering_trader())

    def can_barter(self):
        return self.all_of(
            self.access_region(REGION_NETHER),
            self.entity(E_PIGLIN),
            self.material(MAT_GOLD),
        )

    def can_get_beacon_base(self):
        """A block valid for a beacon pyramid base — any of iron / gold / emerald / diamond /
        netherite. Required by the construct_beacon trigger whenever the beacon needs a pyramid
        (level >= 1); the cheapest reachable one satisfies it."""
        sources = [
            self.acquire("minecraft:iron_block"),
            self.acquire("minecraft:gold_block"),
            self.acquire("minecraft:emerald_block"),
            self.acquire("minecraft:diamond_block"),
            self.acquire("minecraft:netherite_block"),
        ]
        return self.any_of(*[node for node in sources if node is not None])

    # -----------------------------------------------------------------------
    # AP Items
    # -----------------------------------------------------------------------
    def material(self, tier: int):
        # The tier count enforces the PICKUP lock (MaterialLockService): with that route opened the
        # material can simply be taken, so the permissive graph drops the count and keeps only the
        # dimension floor below — the ore still lives where it lives.
        node = Const(True) if self.route_open("pickup") else Has(
            self.player, ITEM_MATERIAL_HANDLING, tier)
        # A material tier carries the dimension its ore lives in, so a requirement gates on reaching
        # that dimension — not just the item count (single source of truth: every caller, curated or
        # compiled, inherits the floor). Copper/iron/diamond are Overworld-only ores; netherite is the
        # Nether (ancient debris). Stone and gold exist in BOTH the Overworld and the Nether
        # (cobblestone/blackstone, overworld/nether gold ore), so they gate on either. Wood tier is
        # ``has(..., 0)`` (trivially true), so it needs no floor.
        if tier >= MAT_NETHERITE:
            return self.all_of(node, self.access_region(REGION_NETHER))
        if tier in (MAT_COPPER, MAT_IRON, MAT_DIAMOND):
            return self.all_of(node, self.access_region(REGION_OVERWORLD))
        if tier in (MAT_STONE, MAT_GOLD):
            return self.all_of(node, self.any_of(self.access_region(REGION_OVERWORLD),
                                                 self.access_region(REGION_NETHER)))
        return node

    def knowledge(self, item: str):
        # A gate the seed switched off (knowledge_gates) has no item in the pool, so requiring it would
        # be unsatisfiable — and the thing it guards is free from the start. Const(True) folds away in
        # and_/or_, so those rules come out as if the gate had never been written.
        if item not in self.active_knowledges:
            return Const(True)
        return Has(self.player, f"{KNOWLEDGE_PREFIX}{item}")

    def inventory_slots(self, count: int):
        # Same "no requirement" shape as self.knowledge: 0 means either the lock isn't in a mode
        # that generates this item, or the slot group in question isn't part of it this seed.
        if count <= 0:
            return Const(True)
        return self.has(ITEM_INVENTORY_SLOT, count)

    # -----------------------------------------------------------------------
    # Item acquisition (used by the trigger compiler to resolve item criteria)
    #
    # ``acquire`` reads the acquisition table (tools/build_acquisition.py) and turns an item's data
    # sources into AST: recipes recurse into their ingredients, mob drops into ``entity``, mining
    # into a pickaxe + material tier, trades into ``can_trade_villager``, chest loot into
    # ``structure``. A recursion stack breaks the ingot⟷block recipe cycles; items with no usable
    # source fall back to the tool/material gates (``_acquire_fallback``), else ``None``.
    # -----------------------------------------------------------------------
    _MAX_DEPTH = 4
    _SIZE_CAP = 1500  # serialized bytes; larger trees collapse to their region floor (see _coarsen)

    def acquire(self, item_id: str, _stack: frozenset = frozenset()):
        base = item_id.split(":", 1)[-1] if ":" in item_id else item_id
        if base.startswith("#"):
            return None  # a raw tag (recipe tags are pre-expanded; a bare tag can't be resolved)
        if base in _stack:
            return None  # recipe cycle — this path can't justify itself
        if len(_stack) >= self._MAX_DEPTH:
            # Too deep to keep expanding. A base material bottoms out at an ore/region regardless, so
            # its tier floor is a sound TERMINAL here — this is not the lossy top-level bypass (a
            # shallow acquire still uses the real sources, e.g. diamond via bastion loot); it only
            # stops the runaway recursion of a deep crafting chain (waxed_copper_lantern → … →
            # copper_ingot). A non-material this deep gives up.
            tier = _MATERIAL_TIER_BY_ITEM.get(base)
            return self.material(tier) if tier is not None else None
        # Memoize on (base, stack): the result is pure for this helper, so the same item is computed
        # once and shared. Datapack compilation calls acquire ~9M times for far fewer distinct keys.
        key = (base, _stack, self._finding_stack, self._pricing_trade)
        cache = self._acquire_cache
        if key in cache:
            return cache[key]
        cache[key] = result = self._acquire_compute(base, _stack)
        return result

    def _acquire_compute(self, base: str, _stack: frozenset):
        # Wood is free once its dimension is reached; collapse it instead of fanning out variants.
        wood_region = _wood_region(base)
        if wood_region is not None:
            return self.access_region(wood_region)

        # Dragon's breath has no recipe or loot table — you bottle it from the Ender Dragon's breath
        # mid-fight — so it is modeled here: the dragon must be reachable (and unlocked, when the
        # boss-lock option gates it) plus a glass bottle.
        if base == "dragon_breath":
            return self.all_of(self.entity(E_ENDER_DRAGON), self.acquire("minecraft:glass_bottle"))

        # A froglight is made when a frog eats a small magma cube — the frog spawns only in the
        # Overworld and the magma cube only in the Nether, so it genuinely needs BOTH dimensions.
        # (The table mis-models it as a plain magma-cube drop, which would drop the frog/Overworld.)
        if base in ("ochre_froglight", "pearlescent_froglight", "verdant_froglight"):
            return self.all_of(self.entity(E_FROG), self.entity(E_MAGMA_CUBE))

        # Saplings that grow in exactly one biome. The tree is the only place the sapling exists, so
        # the real cost is finding that biome — which is what the Biome Finder is for. strict_only,
        # because wandering until you hit a cherry grove is slow but real; the display graph waives
        # it. ('Ecologist' wants all twelve wood types, so it inherits every one of these.)
        if base in _BIOME_SAPLINGS:
            return self.all_of(self.strict_only(self.needs_biome_finder()),
                               self.access_region(REGION_OVERWORLD))

        # Chorus grows only on the OUTER End islands, which are behind a gateway — and a gateway
        # does not exist until the dragon dies. The indexer has no record for these (a chorus flower
        # drops itself when broken, so nothing links it to a source), leaving them free the moment
        # you step through the End portal. That is what made 'Extrabiologist' — plant chorus back in
        # the Overworld — ask for nothing but standing in the End.
        if base in ("chorus_flower", "chorus_plant", "chorus_fruit", "popped_chorus_fruit"):
            return self.outer_end()

        # A grass block only comes up whole with Silk Touch; without it you get dirt. The one way
        # round it is an enderman, which picks a grass block up and sets it down again — real, but
        # not something strict logic should lean on, so it stays in the glitch graph.
        if base == "grass_block":
            return self.any_of(
                self.all_of(self.knowledge(K_SHOVEL), self.can_silk_touch(_stack | {base})),
                self.glitch_only(self.entity(E_ENDERMAN)),
            )

        # Sniffer seeds are dug up by a sniffer, and a sniffer comes from an egg brushed out of a
        # warm ocean ruin — the same gate 'Smells Interesting' carries. Without this the seeds have
        # no record at all and 'Planting the Past' was free.
        if base in ("torchflower_seeds", "pitcher_pod"):
            return self.entity(E_SNIFFER)

        # Everything the Pale Garden makes exists in that one biome and nowhere else, so obtaining
        # any of it is finding the biome. The creaking heart carries the mob on top: it is only a
        # creaking heart while the creaking it spawns is alive.
        if base in _PALE_GARDEN_BLOCKS:
            found = self.all_of(self.strict_only(self.needs_biome_finder()),
                                self.access_region(REGION_OVERWORLD))
            # Finding the biome is ON TOP of the block's own price, not instead of it. Returning the
            # biome alone threw away everything _acquire_from_sources knows, and for the two blocks
            # that only drop to a tool that mattered: pale oak leaves and pale moss want shears or
            # Silk Touch (block_mining says so), so a pale leaf block was obtainable with a Biome
            # Finder and nothing else while ordinary oak leaves correctly asked for Knowledge: Shear
            # Handling. The creaking heart carries the mob on top: it is only a creaking heart while
            # the creaking it spawns is alive.
            sources = self._acquire_from_sources(base, _stack | {base})
            parts = [found] if sources is None else [found, sources]
            if base == "creaking_heart":
                parts.append(self.entity(E_CREAKING))
            return self.all_of(*parts)

        # An elytra exists only in an End City ship — placed in an item frame, not a loot table the
        # indexer reads — so it has no acquisition record and would fall back to its bare material
        # tier (MAT_WOOD, trivially true), dropping the End requirement entirely. Gate it explicitly
        # on the End City (region The End + any structure lock) plus Knowledge: Flying.
        if base == "elytra":
            return self.all_of(self.knowledge(K_FLYING), self.structure(S_END_CITY))

        # A dragon head sits on the End City ships' spires (no recipe or loot table, like the elytra),
        # so it has no acquisition record — gate it on reaching an End City.
        if base == "dragon_head":
            return self.structure(S_END_CITY)

        # The dragon egg appears on the exit portal only once the dragon has been killed, and it
        # drops itself when mined — so the self-mine heuristic read it as a naturally occurring End
        # block and "The Next Generation" needed nothing but standing in the End.
        if base == "dragon_egg":
            return self.outer_end()

        # A sniffer egg is brushed out of the suspicious sand in a warm ocean ruin. Its table lists
        # that structure, but at 6.7% it demotes to a glitch route, leaving strict logic with only
        # `mining: [sniffer_egg]` — and the self-mine heuristic reads that as a naturally occurring
        # block because the egg has no recipe. It has no recipe because it isn't crafted; the block
        # never generates, you place one you already brushed. So the self-mine is as circular as a
        # crafted block's, and taking it at face value made "Smells Interesting" (and the sniffer
        # behind it) free in the Overworld. Model the real route: a brush, and the ruin to use it in.
        if base == "sniffer_egg":
            return self.all_of(self.has_brush(), self.structure(S_OCEAN_RUIN_WARM))

        # Emeralds come out of a villager, and in strict logic out of nothing else.
        #
        # The dump lists three other families and every one of them is a route AP should not plan
        # around. Emerald ore generates in ONE biome group (the windswept/mountain set) in one-block
        # veins, so "mine it" is really "wander until you find that biome" — the same thing the
        # Biome Finder exists for, and not a thing fill may assume; the smelting recipes are that
        # same ore wearing a furnace. The chest routes (shipwreck, buried treasure, the five
        # villages, a desert pyramid …) sit above the glitch threshold on paper, so _demote kept
        # them, and they left emeralds — the currency every trade is priced in — reading as loot
        # rather than as the thing a village gives you.
        #
        # So: sell to a villager. can_sell_to_villager, not can_trade_villager, because a sell offer
        # is what pays you and must not be charged the emeralds it hands over (that is also what
        # keeps _trade_currency's cycle from re-entering here). Everything else stays in the glitch
        # graph, where a player who does find an emerald vein is not told they cannot have it.
        if base == "emerald":
            sell = self.can_sell_to_villager()
            if not self.glitch:
                return self._with_reward(base, sell)
            sources = self._acquire_from_sources(base, _stack | {base})
            found = sell if sources is None else self._unique_or([sell, sources])
            return self._with_reward(base, self._coarsen(found))

        # A filled bucket is made by a USE interaction — right-click a fluid, a mob or a cauldron
        # with an empty bucket — and that act appears in no recipe, loot table or trade, so the dump
        # has no record of it. Two different failures followed from that, and this branch is here to
        # end both:
        #
        #   * an item with NO record (lava, powder snow, axolotl, tadpole, salmon) fell through to
        #     the `*_bucket` suffix rule in _acquire_fallback, which strips the suffix and asks for
        #     the empty bucket — never for what fills it. An axolotl bucket, a tadpole bucket and a
        #     salmon bucket compiled to rules byte-identical to a plain bucket: no axolotl, no frog.
        #   * an item WITH a record never reached that fallback at all (_acquire_from_sources
        #     returned non-None), so it was priced purely by whatever incidental route the dump
        #     happened to find it on: milk was "loot a trial chamber" with no cow and no bucket in
        #     the rule, water was "loot a village", and pufferfish/tropical fish were a lone
        #     Wandering Trader offer, which coarsens to a bare region and left them free.
        #
        # So model the real route for EVERY filled bucket — the bucket, plus whatever fills it — and
        # OR the record's own routes alongside it, because a bucket of fish bought from a trader or
        # pulled out of a chest genuinely needs no bucket of your own.
        # A creeper killed by a skeleton drops a disc from #creeper_drop_music_discs (the creeper loot
        # table's `attacker` condition). The dump records no such source, so ward, chirp, far, mall,
        # mellohi, stal, strad, wait, 11 and blocks had none at all, and every check wanting one fell
        # back to its parent chain — 'All the Items!' came out as reaching 'All the Blocks!'.
        if base in _item_tag("minecraft:creeper_drop_music_discs"):
            routes = [self.all_of(self.has_any_entities(E_SKELETON, E_STRAY, E_BOGGED, E_PARCHED),
                                  self.entity(E_CREEPER))]
            sources = self._acquire_from_sources(base, _stack)
            if sources is not None:
                routes.append(sources)
            return self._with_reward(base, self._coarsen(self._unique_or(routes)))

        structure_pot = _STRUCTURE_POT_SHERDS.get(base)
        if structure_pot is not None:
            breakers = [self.acquire(f"minecraft:{tool}", _stack | {base})
                        for tool in sorted(_item_tag("minecraft:breaks_decorated_pots"))]
            breakers = [node for node in breakers if node is not None]
            if structure_pot not in self.active_structures or not breakers:
                return None
            return self.all_of(self.structure(structure_pot), self.any_of(*breakers))

        if base.endswith("_bucket"):
            routes = []
            bucket = self.acquire("minecraft:bucket", _stack | {base})
            if bucket is not None:
                mobs = _BUCKET_CONTENT_MOBS.get(base, ())
                content = self.any_of(*[self.entity(name) for name in mobs]) if mobs else Const(True)
                routes.append(self.all_of(bucket, content))
            sources = self._acquire_from_sources(base, _stack)
            if sources is not None:
                routes.append(sources)
            if not routes:
                return None
            return self._with_reward(base, self._coarsen(self._unique_or(routes)))

        # Tools / armor / gated craftables (bow, fishing rod, shears, …) need their Knowledge to be
        # USED however they were obtained — but they are still obtained via their real sources, each
        # carrying its own region. So gate on the Knowledge AND the obtainability (recipe ingredients,
        # structure loot, a trade, a drop, …), not a lossy material-tier proxy that drops the
        # ingredients' regions entirely (e.g. a fishing rod's string → spider/cobweb → Overworld). The
        # sources are coarsened first so a big region-only tree collapses to its region floor instead
        # of bloating every tool rule.
        if base in TOOL_LOCKS:
            knowledge_name, tier = TOOL_LOCKS[base]
            sources = self._acquire_from_sources(base, _stack)
            obtain = self._coarsen(sources) if sources is not None else self.material(tier)
            gates = [self.knowledge(knowledge_name)]
            # The mod's tool lock asks for the tier too, on every route the item arrives by
            # (MaterialLockService: Knowledge AND Material Handling >= tier). A crafted tool carries
            # it through its ingredients, but a traded, looted or dropped one did not: a stone pickaxe
            # bought from a toolsmith read as obtainable with no Material Handling at all. The lock is
            # only shipped while its Knowledge gate is on, so the tier is only asked for then.
            if tier > 0 and knowledge_name in self.active_knowledges and not self.route_open("pickup"):
                gates.append(self.has(ITEM_MATERIAL_HANDLING, tier))
            # Armor pieces additionally need an armor slot to wear them in (inventory_lock): having
            # the Knowledge and the material is not enough if there is nowhere to put the thing on.
            if knowledge_name == K_ARMOR:
                gates.append(self.inventory_slots(self.armor_unlock_items))
            # A tool granted as a reward still needs its Knowledge (and, for armor, a slot) to be
            # used, so the reward joins `obtain` (inside the gates), not the whole node.
            return self.all_of(*gates, self._with_reward(base, obtain))

        # A gated station/container block is the same shape as a tool: its Knowledge blocks crafting and
        # picking it up (tool_locks), so obtaining the BLOCK ITEM needs the Knowledge on top of its
        # ordinary sources. Its own recipe is additionally gated on the station that makes it, which
        # _recipe_node already applies.
        block_knowledge = BLOCK_KNOWLEDGE.get(f"minecraft:{base}")
        if block_knowledge is not None:
            sources = self._acquire_from_sources(base, _stack)
            if sources is not None:
                return self.all_of(self.knowledge(block_knowledge),
                                   self._with_reward(base, self._coarsen(sources)))

        sources = self._acquire_from_sources(base, _stack)
        result = self._coarsen(sources if sources is not None else self._acquire_fallback(base))
        # Universal Material Handling pickup lock: obtaining a tier-gated raw material by ANY in-world
        # route — mining, chest loot, mob drop, villager trade, crafting — is blocked until enough
        # Progressive Material Handling is received (MaterialLockService, enforced on floor pickup by
        # ItemEntityMixin AND on container/crafting takes by SlotMixin). Only the mining path carried
        # this gate, so _unique_or absorption could drop the gated branch and leave the material
        # reachable via a bare chest/region path (e.g. diamond looted from a structure). Gate the whole
        # obtain-it node on the tier count — no region floor, since each source carries its own region.
        tier = _MATERIAL_TIER_BY_ITEM.get(base)
        # route_open("pickup"): with the pickup route opened the material can just be taken, so the
        # permissive graph drops the count here too — this is the second place the lock is applied
        # (self.material is the other), and missing it would leave the relaxation half-done.
        if tier is not None and result is not None and not self.route_open("pickup"):
            result = self.all_of(self.has(ITEM_MATERIAL_HANDLING, tier), result)
        # The BACAP reward grants the item via a /give, which adds straight to the inventory and so
        # bypasses both pickup mixins — a real, lock-free way to get it — so it joins OUTSIDE the
        # material gate.
        return self._with_reward(base, result)

    def _with_reward(self, base: str, node):
        """OR a BACAP advancement reward into an item's obtainability — GLITCH GRAPH ONLY.

        A reward is an alternate route that depends on finishing other advancements, so strict logic
        ignores it: fill must not hand you a tool through a reward and call Pickaxe Handling
        satisfied. That also means the cycle this would otherwise have to dodge (``acquire ->
        reached -> rule -> acquire``) can't arise, because the graph carrying rewards is never
        filled against — AP's recursive reached() only evaluates the strict graph.

        So the route is stated as ``loc(<granting advancement>)``, which the mod resolves by its own
        fixed point (RuleNode/LogicEvaluation), cycles and all. reward_sources is empty unless
        bacap_rewards is on, so this is a no-op otherwise."""
        if not self.glitch or base not in self.reward_sources:
            return node
        reward = self.any_of(*[self.reached(name) for name in self.reward_sources[base]])
        return reward if node is None else self.any_of(node, reward)

    def _acquire_from_sources(self, base: str, _stack: frozenset):
        """OR over every modeled way to obtain ``base`` (recipe, drop, mining, silk-mining, trade,
        structure loot, archaeology, gameplay), each carrying its region/tier gate; ``None`` when the
        item has no acquisition record or no usable source. Shared by ordinary items and tool/armor
        gates."""
        record = _acquisition_table().get(base)
        if record is None:
            return None
        inner = _stack | {base}
        chances = record.get("chances", {})
        # Sources split in two: dependable ones, and alternates that lean on luck or on non-progression
        # content. _demote decides which of the second list actually goes (see there — a sole source,
        # or one closer to home than anything dependable, is kept whatever its odds).
        options: list = []
        loose: list = []

        def add(node, glitchy: bool = False):
            # Const(False) is not a source, it is the absence of one — an inactive structure, or a
            # route this graph doesn't carry (the Wandering Trader in strict mode). Dropping it here
            # rather than letting or_ swallow it later matters, because _demote counts the lists it
            # is given: a lone Const(False) in `strict` would read as "something dependable exists"
            # and throw away the flimsy routes that are the item's real ones, and a lone Const(False)
            # in `loose` would make an item whose only listed source is a trade read as unobtainable
            # instead of falling through to _acquire_fallback.
            if node is None or (isinstance(node, Const) and not node.value):
                return
            (loose if glitchy else options).append(node)

        def unreliable(kind: str, name: str) -> bool:
            return chances.get(f"{kind}/{name}", 1.0) < self._GLITCH_CHANCE

        for recipe in record.get("recipes", ()):
            # A recipe is deterministic; whether its INGREDIENTS are is decided in their own acquire.
            add(self._recipe_node(recipe, inner))
        for mob_file in record.get("drops", ()):
            name = _entity_by_gid().get(f"minecraft:{mob_file}")
            if name in MOBS_ALL:
                # A drop needs the mob *defeated*, not merely reached: harmless for ordinary mobs
                # (can_defeat == reachability) but correct for boss drops like the Wither's nether
                # star, which must gate on the whole boss fight rather than just entering its arena.
                add(self.can_defeat(name),
                    unreliable("drops", mob_file) or name not in self.progression_mobs)
        mining_blocks = record.get("mining", ())
        # Data-driven: when the item has a tier-gated ORE source, a same-item block carrying no tier
        # info is a circular placed form (e.g. ``redstone_wire`` beside ``redstone_ore`` [iron]) whose
        # bare region path would undercut the ore's tier gate — the tiered ore is the real source.
        has_tiered_ore = any(_block_mining().get(b, {}).get("needs") for b in mining_blocks)
        for block in mining_blocks:
            if has_tiered_ore and _block_mining().get(block) is None:
                continue
            # A potted plant is the flower pot plus the plant, placed by hand or by a village
            # decorator. Mining one back is circular, and reading it as a natural source made the
            # plant free: `potted_wither_rose` handed out a wither rose with no Wither, and
            # `potted_dead_bush` a dead bush with no shears. The plant's own block (and the item's
            # structure route) stay as the real sources.
            if block.startswith("potted_") or block in _PLACED_FORM_BLOCKS:
                continue
            # A block that drops *itself* is only a real "mine it" source when it generates
            # naturally. A placed-only block — a crafted one (slime_block, wool, planks, …), a mob
            # trophy (a skull/head) or a frog-made froglight — must be obtained then placed first,
            # so its self-mining is circular; worse, it yields a bare region path that _unique_or
            # absorption uses to delete the item's real entity/structure gates (e.g. slime_ball via
            # slime_block, or wither_skeleton_skull, dropping their mob gate).
            placed_only = (bool(record.get("recipes"))
                           or base.endswith(("_head", "_skull", "_froglight")))
            # A VARIANT block that spells out the item is the item after somebody placed it and did
            # something to it — a candle on a cake (`candle_cake`), a plant in a pot. Mining one back
            # is as circular as mining the plain placed block, and the free region path it produced
            # let _unique_or absorb the item's real gates: 'The Ritual Begins' stayed free through
            # `candle_cake` even after string started asking for a sword.
            #
            # An ORE is the opposite shape with the same spelling: `redstone_ore` contains `redstone`
            # because it is where redstone COMES FROM. What separates the two is the block, not the
            # name — an ore generates in the world (it has a block_mining entry) and cannot be
            # crafted, while a candle cake, a filled cauldron or a bookshelf is only ever there
            # because somebody made it. On the bare substring test, diamond, coal, emerald, quartz and
            # redstone all lost their ore route and logic believed the only way to a diamond was a
            # chest; lapis lazuli and raw iron kept theirs purely because their names are not
            # substrings of `lapis_ore` / `iron_ore`. Stricter than the game rather than looser, so it
            # leaked nothing — but it priced the ores the pickaxe and material tiers exist for.
            natural_block = (_block_mining().get(block) is not None
                             and not _acquisition_table().get(block, {}).get("recipes"))
            is_variant = block != base and base in block and not natural_block
            if (block == base or (is_variant and placed_only))                     and placed_only and base not in _NATURAL_SELF_MINED:
                # The self-mine is circular (placed-only), but the block may still generate naturally
                # inside a structure's template (structures.json palette) — reaching that structure
                # and mining it there is a genuine source recipes/loot don't capture (e.g. a
                # comparator in an Ancient City, an Overworld path its quartz recipe otherwise hides
                # behind the Nether). Redundant ones (a block whose recipe is already reachable in the
                # structure's dimension) collapse in _unique_or / _coarsen.
                for struct_name in _block_structures().get(base, ()):
                    if struct_name in self.active_structures:
                        add(self.structure(struct_name),
                            struct_name not in self.progression_structures)
                continue
            node = self._mining_node(block, base, stack=inner)
            if node is not None:
                origin = self._block_origin_node(block, inner, outer=_stack)
                if origin is None:
                    continue          # the block cannot exist for this seed — not a source at all
                add(self.all_of(node, origin), unreliable("mining", block))
        for block in record.get("silk_mining", ()):
            # The block yields itself only to a Silk-Touch tool (bee_nest, ice, coral, …): same
            # region/tier as a normal mine PLUS the capability to silk-touch (enchant). Always behind
            # the silk gate, so it is never a free path even when the block is placed-only.
            node = self._mining_node(block, base, silk=True, stack=inner)
            if node is not None:
                add(node, unreliable("silk_mining", block))
        trades = record.get("trades", ())
        professions = {entry[0] for entry in trades if entry}
        if professions - {"wandering_trader"}:
            # A profession villager stays strict: which offers it rolls is luck, but you can keep
            # rerolling a villager you built a village around, and can_trade_villager already carries
            # that cost. Revisit on measured offer weights rather than a guess.
            add(self.can_trade_villager())
        if "wandering_trader" in professions:
            # The Wandering Trader is glitch: it has to spawn near you AND roll the offer you want,
            # and you can't make either happen — the definition of a route AP shouldn't plan around.
            add(self.can_trade_wandering_trader(), True)
        for structure_name in record.get("structures", ()):
            if structure_name in STRUCTURES:
                # Structure loot lives in containers, so reaching the structure isn't enough when the
                # Chest gate is on — you also have to be able to open one. Chest stands in for the whole
                # family here: a few tables put their loot in barrels or pots instead, and demanding the
                # chest Knowledge for those is stricter than reality rather than looser, which is the
                # safe direction for logic. Folds away entirely when the gate is off.
                add(self.all_of(self.structure(structure_name), self._loot_container_node()),
                    unreliable("structures", structure_name)
                    or structure_name not in self.progression_structures)
        for structure_name in record.get("archaeology", ()):
            if structure_name in STRUCTURES:
                # Archaeology loot is not chest loot: a pottery sherd, a sniffer egg or a trail-ruins
                # trim template is BRUSHED out of suspicious sand or gravel, and breaking the block
                # instead destroys what was inside. Both dumps used to fold these tables in with the
                # chests, so the gate came out as Knowledge: Chest — which a player can hold while
                # having no brush, no copper and no way to dig a single sherd out. Ask for the brush
                # (Brush Handling + copper + a feather), which is what the hand-written sniffer_egg
                # branch has always asked for.
                add(self.all_of(self.structure(structure_name), self.has_brush()),
                    unreliable("archaeology", structure_name)
                    or structure_name not in self.progression_structures)
        for table in record.get("gameplay", ()):
            add(self._gameplay_node(table, inner), unreliable("gameplay", table))
        options = self._demote(options, loose)
        return self._unique_or(options) if options else None

    def _demote(self, strict: list, loose: list) -> list:
        """The sources that survive into THIS graph, given the dependable ones and the flimsy ones.

        In glitch mode everything survives — that graph exists to be permissive. In strict mode a
        flimsy source is dropped, but only when the item is still obtainable without it:

        * **Nothing dependable left** → keep the lot. A 2.5%-only skull is still the way you get a
          skull, and dropping an item's last source makes it unreachable and the seed unfillable.
        * **Nothing dependable that stays home** → keep the flimsy routes that do. On a Nether start
          the surviving diamond route is Overworld mining, behind Dimension Unlock: Overworld, while
          a bastion sits in the start region: demoting it would make strict logic harder than the
          game and paint an ordinary route yellow.

        Otherwise the flimsy source goes, and the dependable one carries the item — which is what
        pushes Pickaxe Handling and Material Handling into the early spheres.
        """
        if self.glitch or not loose:
            return strict + loose
        if not strict:
            return loose
        if any(self._stays_home(node) for node in strict):
            return strict
        return strict + [node for node in loose if self._stays_home(node)]

    def _stays_home(self, node) -> bool:
        """True when a source never leaves the dimension the player starts in — no region leaf at
        all (free anywhere) or only the start region."""
        _gated, regions = node.gate_summary()
        return not regions or regions <= {self.start_region}

    def _coarsen(self, node):
        """Bound the serialized tree: a recipe-combinatorial item (dyes, beds, stews) past
        ``_SIZE_CAP`` whose only leaves are region reachability collapses to the OR of its regions.
        A tree that carries a real gate — a ``Has`` (structure/entity unlock, knowledge, material)
        or a reached-location — is left intact even when large, so a structure/mob lock or
        progression gate is never silently dropped (else a locked source would look reachable)."""
        if node is None or node.serialized_size() <= self._SIZE_CAP:
            return node
        gated, regions = node.gate_summary()
        if gated or not regions:
            return node
        return or_(*[self.access_region(region) for region in sorted(regions)])

    @staticmethod
    def _unique_or(nodes):
        """OR of ``nodes``, deduplicated and absorption-simplified. Besides dropping identical
        sources, this applies ``A ∨ (A ∧ B) = A``: a source that is an AND containing another,
        simpler source as one of its conjuncts is redundant and removed. That collapses the common
        explosion where an item is obtainable trivially (e.g. ``region(Overworld)`` from a cow) and
        also via a far heavier path that still needs that same trivial step (a bred-mob drop)."""
        seen = set()
        unique = []
        for node in nodes:
            key = node.key()
            if key not in seen:
                seen.add(key)
                unique.append(node)

        keep = []
        for node in unique:
            if isinstance(node, And):
                conjunct_keys = {child.key() for child in node.children}
                if conjunct_keys & (seen - {node.key()}):  # a simpler sibling is one of this AND's conjuncts
                    continue
            keep.append(node)
        return or_(*keep)

    def _recipe_node(self, recipe: dict, stack: frozenset):
        """A recipe is satisfied when every distinct ingredient is obtainable (AND), and the station it
        runs on is usable."""
        parts = []
        station = self._station_node(recipe.get("station"), stack)
        if station is not None:
            parts.append(station)
        for ingredient in recipe.get("ingredients", ()):
            node = self._ingredient_node(ingredient, stack)
            if node is None:
                return None  # an unobtainable ingredient disqualifies the whole recipe
            parts.append(node)
        return and_(*parts) if parts else None

    def route_open(self, route: str) -> bool:
        """True when this graph should ignore ``route``'s gate: the seed turned the route off in
        item_gate_behavior AND we are building the permissive graph. Strict logic always says False —
        see gate_routes for why keeping the gate there is the safe direction."""
        return self.glitch and not self.gate_routes.get(route, True)

    def _loot_container_node(self):
        """The Knowledge needed to open structure loot (the chest gate), or a free node when it's off.

        Also free when the seed opened the ``container`` route, since then no Knowledge stands between
        the player and a structure chest at all."""
        if self.route_open("container"):
            return Const(True)
        return self.knowledge(BLOCK_KNOWLEDGE.get("minecraft:chest", ""))

    def _station_node(self, station: str | None, stack: frozenset = frozenset()):
        """What a recipe's station costs: permission to use one, AND one to use.

        ``station`` is the recipe type the pack dumped (``smelting``, ``stonecutting``,
        ``crafting_shaped``, …). RECIPE_STATION_KNOWLEDGE maps it to the Knowledge that unlocks it
        and RECIPE_STATION_BLOCKS to the block(s) that run it, each ORed because either a Crafting
        Table or a Crafter does crafting. Gates the seed switched off fold away in self.knowledge.

        The BLOCK is the half that was missing, and it is not the same requirement as the Knowledge:
        a seed with `knowledge_gates: [All, -Furnace]` used to price a smelt at nothing at all, so
        glass came out FREE while the furnace that makes it was correctly gated behind a pickaxe and
        a stack of cobblestone. That is how BACAP's 'Translucence' — all sixteen stained glass —
        landed in sphere 1. You need a furnace to smelt whether or not an AP item gates its use, so
        the block is required regardless of route_open, which only waives the Knowledge.

        CRAFTING is the exception, on both halves. Every ``crafting_*`` type is treated as needing
        the station: the dump doesn't record a shaped recipe's grid size, so a 2x2 recipe you could
        do in your own inventory is indistinguishable from a 3x3 one (see docs — the 2x2 gap is a
        known open question). Asking for the crafting table ITEM there would also be circular, since
        a crafting table is itself crafted, so crafting keeps the Knowledge-only treatment.
        """
        if not station:
            return None
        key = "crafting" if station.startswith("crafting") else station
        parts = []
        # item_gate_behavior splits the GUI gates: hand-crafting is the `crafting` route, everything
        # that runs on a placed block (smelting, stonecutting, smithing …) is `station`. With the
        # seed's route open the permissive graph asks for no Knowledge — but still for the block.
        if not self.route_open("crafting" if key == "crafting" else "station"):
            names = RECIPE_STATION_KNOWLEDGE.get(key)
            if names:
                # A crafter is itself crafted on a crafting table, so Knowledge: Crafter alone crafts
                # nothing — it read as "can craft" and put composters in logic with no way to make one.
                table = self.knowledge("Crafting Table") if key == "crafting" else self.all_of()
                parts.append(self.any_of(*(self.all_of(self.knowledge(name), table) for name in names)))
        if key != "crafting":
            # Obtaining the station, threading the recipe's own stack so a station that somehow
            # depends on its own output drops out (None) instead of recursing. If every candidate
            # drops out, the Knowledge alone carries the recipe rather than making it unobtainable.
            blocks = [self.acquire(block, stack) for block in RECIPE_STATION_BLOCKS.get(key, ())]
            blocks = [node for node in blocks if node is not None]
            if blocks:
                parts.append(self.any_of(*blocks))
        return self.all_of(*parts) if parts else None

    def _ingredient_node(self, ingredient: dict, stack: frozenset):
        if "any_of" in ingredient:
            options = [self._ingredient_node(sub, stack) for sub in ingredient["any_of"]]
            options = [node for node in options if node is not None]
            return or_(*options) if options else None
        if "item" in ingredient:
            return self.acquire(ingredient["item"], stack)
        return None

    def _mining_node(self, block: str, item: str, silk: bool = False,
                     stack: frozenset = frozenset()):
        """Break ``block`` (to obtain ``item``): be in the block's dimension, and — only for
        pickaxe-mineable blocks (soul sand, glowstone, crops drop bare-handed) — hold a pickaxe of
        the required material tier. ``silk`` adds the Silk-Touch capability when the block yields
        itself only to a Silk-Touch tool.

        Some blocks yield a given item only to a particular tool, which lives in the loot table
        rather than a tag: grass and ferns drop seeds bare-handed but themselves only to shears,
        leaves and cobweb want shears or Silk Touch, a mushroom block yields itself only to Silk
        Touch. block_mining's ``drops`` records that per item, so the gate lands on glow lichen and
        dead bush without touching the wheat seeds off the same grass."""
        parts = [self.access_region(_block_region(block))]
        info = _block_mining().get(block)
        if info is not None:
            # A block is listed for either reason now — a pickaxe tier, or a per-item tool. Only the
            # first means "you need a pickaxe"; glow lichen is in the table and mines bare-handed.
            if "needs" in info:
                parts.append(self.knowledge(K_PICKAXE))
                tier = _MATERIAL_TIER_BY_ITEM.get(item) or _NEEDS_TIER.get(info.get("needs"))
                if tier is not None:
                    parts.append(self.material(tier))
            if not silk:
                tool_gate = self._drop_tool_node(block, info, item, stack)
                if tool_gate is None:
                    return None      # the only tool that works is itself unreachable here
                parts.append(tool_gate)
        # Applied whether or not the block is in block_mining: this requirement comes from the
        # block's hardness, not from its loot table (see _EXTRA_DROP_KNOWLEDGE).
        knowledge = _EXTRA_DROP_KNOWLEDGE.get((block, item))
        if knowledge is not None:
            parts.append(self.knowledge(knowledge))
        if silk:
            silk_gate = self.can_silk_touch(stack)
            if silk_gate is None:
                return None      # silk is unreachable from here (circular) — drop the route
            parts.append(silk_gate)
        return self.all_of(*parts)

    def _block_origin_node(self, block: str, stack: frozenset = frozenset(),
                           outer: frozenset | None = None):
        """What a block needs to EXIST before it can be mined (see ``_BLOCK_ONLY_FROM``), or an
        empty AND for the ordinary block that simply generates in the world. ``None`` when the only
        thing that would place it is inactive this seed, so the caller drops the route.

        ``stack`` is the acquisition recursion stack, needed by the crop route: a crop block exists
        only because its seed was planted (see ``_PLANTED_CROPS``), and for potato/carrot that seed
        is the item being acquired — the stack is what turns that into the cycle it is instead of
        infinite recursion.

        ``outer`` is that stack without the item being acquired, and only the aging route uses it:
        weathering is the SAME item a while later, not another crafting step, so it must not spend a
        depth level of its own — with one it spends, the four-deep waxed-lantern chain (waxed ->
        exposed -> plain -> copper torch -> copper nugget) runs out of ``_MAX_DEPTH`` and the waxed
        lanterns lose their last source. Cycles still terminate: the plain item is on the stack for
        everything below it, so a route back into the aged block dead-ends one level down."""
        seed = _PLANTED_CROPS.get(block)
        if seed is not None:
            return self.acquire(seed, stack)
        aged = _aged_source(block)
        if aged is not None:
            # The plain item, weathered — plus mining one that generated already aged, which only the
            # structure palettes know about (and which _unique_or collapses when it is redundant).
            aged_stack = stack if outer is None else outer
            routes = [route for route in (self.acquire(aged, aged_stack),) if route is not None]
            routes += [self.structure(name) for name in _block_structures().get(block, ())
                       if name in self.active_structures]
            return self.any_of(*routes) if routes else None
        entry = _BLOCK_ONLY_FROM.get(block)
        if entry is None:
            return self._placed_block_origin(block, stack)
        kind, value = entry
        if kind == "boss":
            return self.can_defeat(value)
        if kind == "entity":
            return self.entity(value)
        if kind == "structures_or_craft":
            names, tool_id, source_id = value
            routes = [self.structure(name) for name in names if name in self.active_structures]
            tool, source = self.acquire(tool_id), self.acquire(source_id)
            if tool is not None and source is not None:
                routes.append(self.all_of(tool, source))
            return self.any_of(*routes) if routes else None
        active = [name for name in value if name in self.active_structures]
        return self.any_of(*[self.structure(name) for name in active]) if active else None

    def _placed_block_origin(self, block: str, stack: frozenset):
        """Origin of a block nobody finds lying around, derived rather than curated: one whose record
        has a recipe and nothing that generates it — no gameplay or silk-mining source, and nothing
        mined but itself (a chest, a drop or a trade gives you the item, not a placed block). Such a block is where it is because a player crafted it or a
        structure's palette placed it, so mining it for what it drops costs one of those two.

        The case that found this: obsidian lists ``ender_chest`` among its mining blocks, because
        breaking one drops its 8 obsidian. Nothing asked you to HAVE an ender chest, so obsidian —
        and through the Nether portal edge, ``We Need to Go Deeper`` — was priced at a pickaxe and a
        dimension. An ender chest is crafted from 8 obsidian and an eye of ender, and the only place
        one generates is an End City: the craft route closes as the cycle it is (obsidian is already
        on the stack), leaving the structure, which is where that route honestly belongs.

        Blocks that really do generate keep costing nothing: stone, clay, glowstone, deepslate and
        the rest carry their own mining/loot sources in the record, so they never reach the test.
        ``None`` when neither origin exists this seed — the caller then drops the route."""
        if block in _NATURAL_SELF_MINED:
            return self.all_of()          # curated: it really is lying around out there
        record = _acquisition_table().get(block)
        if not record or not record.get("recipes"):
            return self.all_of()
        # Only a source that PUTS the block somewhere says it can be found standing. Loot, drops,
        # archaeology and trades hand you the item, which still has to be placed — and that is what
        # the crafted route below prices, since acquire() already includes them. Counting a trade as
        # "lying around" made a fisherman's campfire free to break, and so charcoal free with it.
        if any(record.get(key) for key in ("gameplay", "silk_mining")):
            return self.all_of()
        if any(mined != block for mined in record.get("mining", ())):
            return self.all_of()
        routes = [self.structure(name) for name in _block_structures().get(block, ())
                  if name in self.active_structures]
        crafted = self.acquire(f"minecraft:{block}", stack)
        if crafted is not None:
            routes.append(crafted)
        return self.any_of(*routes) if routes else None

    # Loot-table tool name -> the capability that satisfies it.
    def _drop_tool_node(self, block: str, info: dict, item: str, stack: frozenset):
        """Gate for the tool ``item`` needs off this block, or an empty AND when it needs none.

        ``None`` means the requirement exists but no listed tool is reachable — the caller drops the
        whole mining route rather than pretending the drop is free."""
        tools = (info.get("drops") or {}).get(item)
        if not tools:
            return self.all_of()
        routes = []
        for tool in tools:
            if tool == "shears":
                shears = self.acquire("minecraft:shears", stack)
                if shears is not None:   # None on a cycle (shears off a shears-gated block)
                    routes.append(shears)
            elif tool == "silk":
                silk_gate = self.can_silk_touch(stack)
                if silk_gate is not None:
                    routes.append(silk_gate)
        return self.any_of(*routes) if routes else None

    def can_silk_touch(self, stack: frozenset = frozenset()):
        """The capability to wield a Silk-Touch tool. Two routes, mirroring the enchant gate in
        ``triggers.py``: enchant one yourself (``acquire(enchanting_table)`` gates Knowledge:
        Enchanting + its tier), OR apply a Silk-Touch enchanted book with an anvil (a librarian's
        book is a no-Knowledge trade path).

        ``stack`` is the acquisition recursion stack. It must be threaded through: a silk-only block
        drop asks for silk, silk asks for an enchanting table, and its ingredients can lead back to a
        silk-only block (deepslate gold ore -> silk -> obsidian -> ... -> deepslate gold ore). Without
        the stack that loop never breaks. ``None`` when every route is itself circular."""
        routes = []
        table = self.acquire("minecraft:enchanting_table", stack)
        if table is not None:
            routes.append(table)
        book = self.acquire("minecraft:enchanted_book", stack)
        anvil = self.acquire("minecraft:anvil", stack) if book is not None else None
        if book is not None and anvil is not None:
            routes.append(self.all_of(book, anvil))
        return self.any_of(*routes) if routes else None

    def _gameplay_node(self, table: str, stack: frozenset = frozenset()):
        """The gate for a 'gameplay' loot source — a real, repeatable acquisition path, grounded in
        the 26.1.2 jar loot-table types. ``stack`` is the acquisition recursion stack, threaded into
        the items a source implies (a fishing rod for fishing, gold for bartering, shears for harvest)
        so a circular source — fishing up the very fishing rod being resolved — breaks instead of
        recursing forever:
          * fishing tables → a fishing rod;
          * piglin_bartering → the Nether, a piglin and gold;
          * a mob's gift / interaction / growth table → reach that mob (gift tables ARE reliable
            renewable sources — see ``_GAMEPLAY_MOB``);
          * a charged-creeper head/skull table → charge a creeper (a creeper + an Overworld
            thunderstorm) AND reach the victim (see ``_CHARGED_CREEPER_VICTIM``);
          * a Hero-of-the-Village villager profession gift → win a raid (a pillager + a village);
          * a block-harvest table → the block's dimension, plus shears for a shear interaction;
          * trial-chamber spawner equipment / chest loot → the Trial Chambers structure."""
        if table in ("fishing", "fish", "junk", "treasure"):
            return self.acquire("minecraft:fishing_rod", stack)  # None on a cycle → caller drops it
        if table == "piglin_bartering":
            gold = self.acquire("minecraft:gold_ingot", stack)
            if gold is None:
                return None  # circular (bartering FOR gold) — not a usable source here
            return self.all_of(self.access_region(REGION_NETHER), self.entity(E_PIGLIN), gold)
        victim = _CHARGED_CREEPER_VICTIM.get(table)
        if victim is not None:
            # Head only drops from a CHARGED creeper's kill: charge a creeper (creeper + Overworld
            # thunderstorm) AND reach the victim. dict.fromkeys dedups when the victim is a creeper.
            mobs = list(dict.fromkeys([E_CREEPER, victim]))
            return self.all_of(*[self.entity(name) for name in mobs],
                               self.access_region(REGION_OVERWORLD))
        mob = _GAMEPLAY_MOB.get(table)
        if mob is not None:
            return self.entity(mob)
        if table in _GAMEPLAY_VILLAGER_GIFTS:
            return self.can_win_raid()  # villagers throw gifts after a won raid
        harvest = _GAMEPLAY_HARVEST.get(table)
        if harvest is not None:
            region, needs_shears = harvest
            if needs_shears:
                shears = self.acquire("minecraft:shears", stack)
                if shears is None:
                    return None  # can't shear-harvest without shears (circular here)
                return self.all_of(self.access_region(region), shears)
            return self.access_region(region)
        if table in ("corridor", "trial_chamber_melee", "trial_chamber_ranged"):
            return self.structure(S_TRIAL_CHAMBERS)
        return None

    def _acquire_fallback(self, base: str):
        """Items absent from the acquisition table (or with no usable source): the tool/armor
        knowledge+material gate, the diamond/netherite mining gates, then the bare material tier."""
        if base in ("diamond", "diamond_block"):
            return self.all_of(self.knowledge(K_PICKAXE), self.material(MAT_DIAMOND),
                               self.access_region(REGION_OVERWORLD))
        if base in ("netherite_ingot", "netherite_block", "netherite_scrap", "ancient_debris"):
            return self.all_of(self.knowledge(K_PICKAXE), self.material(MAT_NETHERITE),
                               self.access_region(REGION_NETHER))
        if base in TOOL_LOCKS:
            knowledge_name, tier = TOOL_LOCKS[base]
            return self.all_of(self.knowledge(knowledge_name), self.material(tier))
        if base == "firework_star":
            # A special recipe type (data/minecraft/recipe/firework_star.json), which the dump does not
            # record: gunpowder and a dye are required, the shape and effect slots optional. With no
            # record the star was unpriceable, and 'All the Items!' fell back to its parent chain.
            stack = frozenset({base})
            dyes = [node for node in (self.acquire(f"minecraft:{dye}", stack)
                                      for dye in sorted(_item_tag("minecraft:dyes"))) if node is not None]
            gunpowder = self.acquire("minecraft:gunpowder", stack)
            if gunpowder is None or not dyes:
                return None
            return self.all_of(self._station_node("crafting_special", stack), gunpowder, self.any_of(*dyes))
        tier = _MATERIAL_TIER_BY_ITEM.get(base)
        if tier is not None:
            return self.material(tier)
        # (A filled bucket used to be caught here, by suffix, and priced as the empty bucket alone.
        # _acquire_compute now models every one of them — bucket AND what fills it — before the
        # fallback is ever consulted.)
        return None
