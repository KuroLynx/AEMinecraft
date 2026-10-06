"""Lookup tables and small predicate readers shared by the advancement compiler (criteria.py).

What each criterion trigger costs lives in criteria.py; this module only holds the game facts it
looks up (block gates, damage tags, effect sources, enchantment loot, brewing, item tags…).
"""
from __future__ import annotations

import json
import re
from functools import cache
from importlib.resources import files

from .ast import Const, Rule, and_, or_
from .constants import (
    E_ARMADILLO,
    E_CAVE_SPIDER,
    E_CHICKEN,
    E_COPPER_GOLEM,
    E_COW,
    E_ENDER_DRAGON,
    E_PIG,
    E_SKELETON,
    E_SPIDER,
    E_WARDEN,
    E_WITHER,
    E_ZOMBIE,
    K_ARMOR,
    K_BREWING,
    K_HOE,
    K_PICKAXE,
    MAT_IRON,
    REGION_END,
    REGION_NETHER,
    REGION_OVERWORLD,
    S_ANCIENT_CITY,
    S_JUNGLE_PYRAMID,
    S_MANSION,
    S_STRONGHOLD,
)
from ..content.registry import overlay_packs

_SMITHING_STATION = "smithing_transform"

# An exploding entity that is not a mob, mapped to the item a player must hold to set it off. Only
# the ones a criterion's `cause` can name need an entry; a mob cause resolves through _entity_gid.
# The station key the pack's container dump records for the smithing table. Trim recipes run on the
# same block as transforms, so they share its Knowledge and its block requirement.
_EXPLOSION_ITEM = {
    "minecraft:primed_tnt": "minecraft:tnt",
    "minecraft:tnt_minecart": "minecraft:tnt_minecart",
    "minecraft:wind_charge": "minecraft:wind_charge",
    "minecraft:breeze_wind_charge": "minecraft:wind_charge",
}


# Triggers that imply a specific tool/block the criterion never names: hitting a target block is
# gated by crafting one (redstone + hay), brewing by a brewing stand, etc. Reaching the implied item
# is the meaningful gate, so the advancement inherits its acquisition logic.
_IMPLIED_ITEM = {
    "minecraft:target_hit": "minecraft:target",
    "minecraft:enchanted_item": "minecraft:enchanting_table",
    "minecraft:fishing_rod_hooked": "minecraft:fishing_rod",
}

# Items that carry a brewed potion (and so gate on the brewing chain, not loot).
_POTION_ITEMS = {
    "minecraft:potion", "minecraft:splash_potion",
    "minecraft:lingering_potion", "minecraft:tipped_arrow",
}

# Armor-trim material (a ``trim`` item predicate names it) -> the item that supplies it at a smithing
# table. The material is the region-binding requirement (netherite/quartz are Nether, the rest
# Overworld); the template and armor are region-neutral (templates appear in structures everywhere,
# armor is trivial), so they aren't modeled.
_TRIM_MATERIAL_ITEM = {
    "amethyst": "amethyst_shard", "copper": "copper_ingot", "diamond": "diamond",
    "emerald": "emerald", "gold": "gold_ingot", "iron": "iron_ingot", "lapis": "lapis_lazuli",
    "netherite": "netherite_ingot", "quartz": "quartz", "redstone": "redstone",
    "resin": "resin_brick",
}

# Enchantments an enchanting table cannot produce. Derived from the 26.1.2 jar's enchantment tags:
# of 43 enchantments, #minecraft:in_enchanting_table holds 36 and #minecraft:tradeable holds 40, so
# these seven are the ones "go enchant something" is the wrong price for. Four remain tradeable, i.e.
# a librarian's enchanted book is the entire route.
_NOT_IN_ENCHANTING_TABLE = frozenset({
    "mending", "frost_walker", "binding_curse", "vanishing_curse",   # tradeable / loot only
    "swift_sneak", "soul_speed", "wind_burst",                       # in neither tag — see below
})

# The three in neither tag: exactly one place in the world generates each, so no amount of enchanting
# or trading substitutes. Loot tables verified in the same jar (paths under data/minecraft/loot_table).
# Each value takes the compiler so the gate builds lazily through _struct_gid (which yields None when
# the active packs lack that structure, rather than inventing a gate).
_ENCHANT_LOOT_SOURCE = {
    "swift_sneak": lambda c: c._struct_gid("minecraft:ancient_city"),        # chests/ancient_city
    "soul_speed": lambda c: c._any_opt(                                      # chests/bastion_other
        c._struct_gid("minecraft:bastion_remnant"), c.h.can_barter()),       # + gameplay/piglin_bartering
    "wind_burst": lambda c: c._struct_gid("minecraft:trial_chambers"),       # trial_chambers/reward_ominous_rare
}

# Damage-type #tags, as gates. BACAP reaches for one of these whenever it wants "kill it WITH
# something" without naming the item — the tag is then the only thing the criterion says about how
# the blow must land, so it IS the requirement. Six occur across the pack; they are tried in this
# order because it runs most specific first (a mace smash is also a player attack, and the mace is
# the answer). A value that resolves to None (an item this content version lacks) falls through to
# the next matching tag rather than voiding the gate.
_DAMAGE_TAG_GATE = {
    "minecraft:mace_smash": lambda c: c.h.acquire("minecraft:mace"),
    "minecraft:spear": lambda c: c.h.acquire("minecraft:spear"),
    "blazeandcave:spear": lambda c: c.h.acquire("minecraft:spear"),
    # Something had to explode: your own TNT or end crystal, a bed/anchor detonated where it cannot
    # be slept in, or a creeper you led into the victim.
    "minecraft:is_explosion": lambda c: c._any_opt(
        c.h.acquire("minecraft:tnt"), c._entity_gid("minecraft:creeper"),
        c.h.acquire("minecraft:end_crystal"), c.h.acquire("minecraft:respawn_anchor")),
    # Kept as bow/crossbow + arrow, matching what _killing_blow_gear already demanded here.
    "minecraft:is_projectile": lambda c: c._all_req(
        c._any_opt(c.h.acquire("minecraft:bow"), c.h.acquire("minecraft:crossbow")),
        c.h.can_get_arrow()),
    "minecraft:is_player_attack": lambda c: c.h.can_kill(),
}

def _pack_json(filename: str, pack: str) -> dict:
    root = __package__.rsplit(".", 1)[0]  # e.g. "worlds.minecraft_aem"
    with files(root).joinpath("packs", pack, filename).open(encoding="utf-8") as f:
        return json.load(f)


@cache
def _tags(content) -> dict:
    """Lazily-loaded item + entity-type tag table (tools/build_tags.py), keyed by tag id, per
    Minecraft version.

    Vanilla plus any optional pack (BACAP) merged in, so BACAP criteria that reference
    ``#blazeandcave:*`` tags (e.g. ``time_to_mine`` → ``#blazeandcave:pickaxes``) resolve instead of
    falling back to the parent chain. Tag namespaces don't collide (``minecraft:`` vs
    ``blazeandcave:``), so a per-registry dict merge is safe; absent pack tags are ignored."""
    merged = _pack_json("tags.json", content.BASE_PACK)
    bacap = overlay_packs(content.version).get("blazeandcave")
    try:
        extra = _pack_json("tags.json", pack=bacap) if bacap else {}
    except (FileNotFoundError, OSError):
        extra = {}
    for registry, tags in extra.items():
        merged.setdefault(registry, {}).update(tags)
    return merged


@cache
def _brewing(content) -> dict:
    """Lazily-loaded potion-type -> reagent items table (packs/.../brewing.json), per Minecraft version."""
    return _pack_json("brewing.json", content.BASE_PACK)


def _reagent_routes(content, potion_type: str) -> list[list[str]] | None:
    """A potion type's brewing routes from brewing.json, each the reagents one chain needs. An entry
    is one route (a flat list, the hand-written tables) or several equally short ones
    (``{"any_of": [[...], ...]}``, from a table dumped off 26.3+'s brewing recipes, where e.g.
    slowness comes off swiftness OR leaping). ``None`` for a type the table doesn't know."""
    entry = _brewing(content).get(potion_type)
    if entry is None:
        return None
    if isinstance(entry, dict):
        return [list(route) for route in entry.get("any_of", [])]
    return [list(entry)]

# Minecraft dimension id -> our region name.
# Keyed by the bare dimension path; lookups go through _path so minecraft:the_end / the_end /
# (any namespace):the_end all resolve. BACAP writes these ids bare.
_DIMENSION_REGION = {
    "overworld": REGION_OVERWORLD,
    "the_nether": REGION_NETHER,
    "the_end": REGION_END,
}

# "Reaching / interacting with an entity" triggers: the criterion names an entity type and the
# rule is simply that the entity is reachable. (Killing it bare-handed is always possible; bosses
# carry their own curated kill rule, so these stay reachability-only here.)
_ENTITY_REACH_TRIGGERS = frozenset({
    "minecraft:entity_killed_player",
})

# Mobs that fire a projectile (for "deflect a projectile with a shield" — the criterion names no
# attacker, so any of these reachable, plus a shield, satisfies it). Skeleton is overworld-universal,
# so this is effectively "a shield + the Overworld".
_PROJECTILE_SHOOTERS = (
    "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:pillager",
    "minecraft:blaze", "minecraft:ghast", "minecraft:witch", "minecraft:drowned",
    "minecraft:breeze",
)

# A few status effects with a non-brewing environmental source (the rest are gated on brewing in
# _effects_node). Each maps the effect id to a builder taking the compiler -> a Rule (or None).
_EFFECT_SOURCE = {
    "minecraft:levitation": lambda c: c._entity_gid("minecraft:shulker"),
    "minecraft:dolphins_grace": lambda c: c._entity_gid("minecraft:dolphin"),
    "minecraft:conduit_power": lambda c: c.h.acquire("minecraft:conduit"),
}

# Custom counter stats whose real prerequisite is simply obtaining an item (the count isn't a logic
# gate): eating cake slices needs access to cake (BACAP's "Must be your birthday").
_CUSTOM_STAT_ITEM = {
    "minecraft:eat_cake_slice": "minecraft:cake",
}

# Custom counter stats with no gameplay gate at all — pure elapsed-time counters: staying awake long
# enough for phantoms (BACAP's "Insomniac") is just waiting, reachable anywhere.
_CUSTOM_STAT_TRIVIAL = frozenset({"minecraft:time_since_rest", "minecraft:time_since_death"})

# Equipment slots an entity predicate can constrain (vanilla EquipmentSlot names). Read by
# _entity_equipment_node so "an entity wearing X" also requires being able to obtain X.
_EQUIPMENT_SLOTS = frozenset({"head", "chest", "legs", "feet", "body", "saddle",
                              "mainhand", "offhand"})

# Non-item natural blocks whose mere presence pins the dimension(s) you can be in to interact with one
# (entering / standing on / placing it). Each maps to the region(s) where that block exists — a block
# carries no acquirable item, and every advancement is placed in the Overworld region, so without this
# an `enter_block`/`stepping_on` on one would leak as Const(True), reachable from spawn (or, on a
# Nether start, reachable without the Overworld). Values are a tuple of regions (the block is in ONE of
# them → OR). Water exists in the Overworld AND the End (never the Nether); powder snow / berry bushes /
# dirt paths are Overworld-only; the portal/vine/soul-fire blocks pin the Nether or the End.
# Blocks whose dimension is not the whole story: something must have happened before one exists at
# all. Applied on top of _BLOCK_REGION by _block_region_node. Keyed by block path, value takes the
# RuleHelper so the gate is built lazily.
_BLOCK_EXTRA_GATE = {
    "end_gateway": lambda h: h.outer_end(),  # spawns only when the dragon dies
}

_BLOCK_REGION = {
    "end_portal": (REGION_END,),
    "end_gateway": (REGION_END,),
    "twisting_vines": (REGION_NETHER,),
    "twisting_vines_plant": (REGION_NETHER,),
    "weeping_vines": (REGION_NETHER,),
    "weeping_vines_plant": (REGION_NETHER,),
    "powder_snow": (REGION_OVERWORLD,),
    "sweet_berry_bush": (REGION_OVERWORLD,),
    "dirt_path": (REGION_OVERWORLD,),
    # Water does NOT generate in the End — placing a bucket there is possible, but no advancement
    # asks for that, and listing the End let 'Stay Hydrated!' satisfy its water half there instead
    # of in the Overworld. Bubble columns need source water, so they follow.
    "water": (REGION_OVERWORLD,),
    "bubble_column": (REGION_OVERWORLD,),
}

# Blocks whose real cost is neither "a dimension" nor "acquire the item" — the two answers the
# compiler reaches for by default, and both wrong here. Consulted FIRST by _block_source_node, so an
# entry overrides _BLOCK_REGION entirely. Keyed by block path; the value takes the RuleHelper so the
# gate is built lazily.
_BLOCK_GATE = {
    # A nether portal block is not Nether-only — you are standing in one the moment you light a
    # frame in the Overworld, and restoring a ruined portal is the usual way to meet this. What it
    # actually costs is the obsidian; can_get_obsidian is itself region-aware, so a Nether-side
    # restoration still resolves to Nether sources.
    "nether_portal": lambda h: h.can_get_obsidian(),

    # Soul sand and soul fire are not Nether-exclusive: an Ancient City generates both down in the
    # Deep Dark, so a player who never lights a portal can still stand in either.
    "soul_sand": lambda h: h.any_of(h.access_region(REGION_NETHER), h.structure(S_ANCIENT_CITY)),
    "soul_soil": lambda h: h.any_of(h.access_region(REGION_NETHER), h.structure(S_ANCIENT_CITY)),
    "soul_fire": lambda h: h.any_of(h.access_region(REGION_NETHER), h.structure(S_ANCIENT_CITY)),

    # Cobweb has no recipe and does not generate in the open: it comes out of a mineshaft, an
    # abandoned village, a stronghold or a woodland mansion.
    "cobweb": lambda h: h.any_of(h.any_mineshaft(), h.any_village(),
                                 h.structure(S_STRONGHOLD), h.structure(S_MANSION)),

    # A filled cauldron is a PLACED block, never an item: the cauldron itself plus the bucket used
    # to fill it — seven iron before you are standing in one.
    "water_cauldron": lambda h: h.all_of(h.acquire("minecraft:cauldron"),
                                         h.acquire("minecraft:water_bucket")),
    "lava_cauldron": lambda h: h.all_of(h.acquire("minecraft:cauldron"),
                                        h.acquire("minecraft:lava_bucket")),

    # A candle cake is not an item — it is a cake you have placed and stuck a candle in. Neither
    # half is free: the cake is milk + sugar + egg + wheat (so a bucket and a cow), the candle is
    # string and honeycomb. Keyed by the shared suffix, see _gate_key.
    "candle_cake": lambda h: h.all_of(h.acquire("minecraft:cake"), h.acquire("minecraft:candle")),

    # Tripwire is the strung block, which has no item form of its own: find it already strung in a
    # jungle pyramid, or make it from the hooks and the string.
    "tripwire": lambda h: h.any_of(
        h.structure(S_JUNGLE_PYRAMID),
        h.all_of(h.acquire("minecraft:tripwire_hook"), h.acquire("minecraft:string")),
    ),
}

# Requirements the game enforces that the criteria never state, AND-ed onto a compiled record by
# game_id. Deliberately a short list: anything derivable from the criteria belongs in a handler, and
# every entry here is a fact about how the advancement is actually done.
# Triggers that cannot fire without a living mob taking part: something killed, hurt, bred, tamed,
# traded with, interacted with, or picking an item up. When a criterion pins no entity type at all,
# the handler has nothing to reach, and several priced only the weapon, the place or the item — so
# 'Death by Magic', 'Arbalistic', 'It Spreads' and "What's Up, Doc?" were reachable with every mob
# locked. An armor stand is no substitute: ArmorStand.kill() removes it without LivingEntity.die, so
# it never awards a kill. entity_killed_player is left out: its killer need not be a mob.
_NEEDS_A_MOB = frozenset({
    "minecraft:player_killed_entity", "minecraft:player_hurt_entity", "minecraft:bred_animals",
    "minecraft:tame_animal", "minecraft:villager_trade", "minecraft:cured_zombie_villager",
    "minecraft:summoned_entity", "minecraft:player_interacted_with_entity",
    "minecraft:kill_mob_near_sculk_catalyst", "minecraft:killed_by_arrow",
    "minecraft:channeled_lightning", "minecraft:spear_mobs", "minecraft:thrown_item_picked_up_by_entity",
})
# From this many of one item a criterion is pricing a STACK, which chest loot does not supply — see
# RuleHelper.bulk_mode. The packs ask for 2, 4, 16 or 64: a pair or four of something can come out
# of one chest, 16 buckets or 64 bone blocks cannot.
_BULK_COUNT = 16
# Criterion keys that name a participating entity (as opposed to a projectile or the damage source).
_PARTICIPANT_KEYS = frozenset({"entity", "victims", "villager", "child", "parent", "partner", "zombie",
                               "bystander"})

# The effects a spider can spawn with on Hard difficulty (Spider$SpiderEffectsGroupData.setRandomEffect).
_SPIDER_SPAWN_EFFECTS = frozenset({"minecraft:speed", "minecraft:strength", "minecraft:regeneration",
                                   "minecraft:invisibility"})

_EXTRA_REQUIREMENT = {
    # Unending Hell: be in the Nether having already been to the End, WITHOUT dying in between
    # (an inverted death score). Surviving that round trip means setting spawn on the Nether side,
    # and the anchor is the only way to do it — the criteria only describe the two dimensions.
    "blazeandcave:end/unending_hell": lambda h: h.acquire("minecraft:respawn_anchor"),
    # Checks BACAP grants from its own functions (`minecraft:impossible` criteria), so the compiler has
    # nothing to read and they fall back to their parent chain. That chain never needed the mob the
    # check is ABOUT — each of these stayed reachable with that mob's unlock removed. Only what the
    # in-game description names is added; the rest of what they cost is still the parent chain's.
    "blazeandcave:animal/beef_moover": lambda h: h.entity(E_COW),                  # unite all Cow variants
    "blazeandcave:animal/the_three_little_pigs": lambda h: h.entity(E_PIG),        # unite all Pig variants
    "blazeandcave:redstone/splatfest": lambda h: h.entity(E_CHICKEN),              # every type of Chicken Egg
    "blazeandcave:challenges/dragon_vs_dragon_ii_electric_boogaloo":
        lambda h: h.can_defeat(E_ENDER_DRAGON),                                     # defeat the Ender Dragon
    "blazeandcave:challenges/dragon_vs_wither_the_pre_sequel": lambda h: h.can_defeat(E_WITHER),
    "blazeandcave:challenges/the_world_is_ending": lambda h: h.summon(E_WITHER),   # summon ten withers
    "blazeandcave:challenges/overwarden": lambda h: h.entity(E_WARDEN),            # fifty Wardens nearby
    "blazeandcave:enchanting/whack_a_mole": lambda h: h.entity(E_ARMADILLO),       # hit eight Armadillos
    "blazeandcave:end/why_do_i_hear_boss_music": lambda h: h.entity(E_ENDER_DRAGON),  # while fighting it
    "blazeandcave:mining/copper_golem_overlord": lambda h: h.entity(E_COPPER_GOLEM),
    "blazeandcave:monsters/bone_to_party": lambda h: h.all_of(h.entity(E_SKELETON), h.entity(E_WITHER)),
    "blazeandcave:monsters/family_reunion": lambda h: h.entity(E_ZOMBIE),
    # Fill the inventory with Totems of Undying: an Evoker is the only source the game data lists.
    "blazeandcave:challenges/immortal": lambda h: h.acquire("minecraft:totem_of_undying"),
}

# Crops that can only be planted in farmland, which only a hoe makes. (Cocoa goes on jungle logs,
# nether wart in soul sand, bamboo/saplings/sweet berries on dirt — none of those need the tool.)
_FARMLAND_CROPS = frozenset({
    "minecraft:wheat", "minecraft:beetroots", "minecraft:carrots", "minecraft:potatoes",
    "minecraft:pumpkin_stem", "minecraft:melon_stem",
    "minecraft:torchflower_crop", "minecraft:pitcher_crop",
})

# Blocks that exist only where they generate and have to be CARRIED anywhere else. Applied when a
# criterion pins a dimension the block is not native to: standing in powder snow in the Nether is
# not a powder-snow gate, it is a bucket gate. Keyed by block path -> the item that moves it.
_BLOCK_TRANSPORT = {
    "powder_snow": "minecraft:powder_snow_bucket",
    "water": "minecraft:water_bucket",
    "lava": "minecraft:lava_bucket",
}




_PLANT_ITEM = {
    "minecraft:torchflower_crop": "minecraft:torchflower_seeds",
    "minecraft:pitcher_crop": "minecraft:pitcher_pod",
    "minecraft:wheat": "minecraft:wheat_seeds",
    "minecraft:beetroots": "minecraft:beetroot_seeds",
    "minecraft:carrots": "minecraft:carrot",
    "minecraft:potatoes": "minecraft:potato",
    "minecraft:pumpkin_stem": "minecraft:pumpkin_seeds",
    "minecraft:melon_stem": "minecraft:melon_seeds",
    "minecraft:cocoa": "minecraft:cocoa_beans",
    "minecraft:sweet_berry_bush": "minecraft:sweet_berries",
    "minecraft:nether_wart": "minecraft:nether_wart",
    "minecraft:bamboo_sapling": "minecraft:bamboo",
    # Fire blocks aren't items — they're placed by igniting a surface with flint and steel or a
    # fire charge (a value may be a list of alternative placing items, OR-ed in _placed_block_node).
    "minecraft:fire": ["minecraft:flint_and_steel", "minecraft:fire_charge"],
    "minecraft:soul_fire": ["minecraft:flint_and_steel", "minecraft:fire_charge"],
}

# Loot tables whose name isn't a structure id: a chest that belongs to a feature the registry
# names differently (a dungeon's monster_room, an underwater ruin's ocean_ruin).
_LOOT_TABLE_STRUCT = {
    "simple_dungeon": "minecraft:monster_room",
    "abandoned_mineshaft": "minecraft:mineshaft",
    "woodland_mansion": "minecraft:mansion",
    "underwater_ruin_big": "minecraft:ocean_ruin_warm",
    "underwater_ruin_small": "minecraft:ocean_ruin_warm",
}


def _path(gid):
    """The bare path of an id (namespace dropped): ``minecraft:end_city`` / ``end_city`` /
    ``bacap:end_city`` all become ``end_city``. The registries hold one path per namespace, so
    matching on the path makes every id form resolve regardless of namespace."""
    return gid.rsplit(":", 1)[-1] if isinstance(gid, str) else gid



def _pins_participant_type(cond) -> bool:
    """Whether a participant predicate names an entity `type` (outside an inverted condition). A
    pinned type is priced by its handler — and may be no mob at all, like Living Dummy's armor
    stand — so only a criterion that pins none gets the generic "some mob" requirement."""
    def walk(node, participant):
        if isinstance(node, list):
            return any(walk(item, participant) for item in node)
        if not isinstance(node, dict) or str(node.get("condition", "")).endswith("inverted"):
            return False
        if participant and isinstance(node.get("type"), str):
            return True
        return any(walk(value, participant or key in _PARTICIPANT_KEYS)
                   for key, value in node.items() if key not in ("damage", "killing_blow"))
    return walk(cond, False)


def _pinned_dimensions(player) -> set:
    """Every dimension a `player` predicate positively requires. Inverted terms are skipped —
    "NOT in the Nether" pins nothing."""
    found: set = set()

    def walk(node) -> None:
        if isinstance(node, list):
            for item in node:
                walk(item)
            return
        if not isinstance(node, dict):
            return
        if str(node.get("condition", "")).endswith("inverted"):
            return
        for term in node.get("terms") or ():
            walk(term)
        pred = node.get("predicate")
        if isinstance(pred, dict):
            location = pred.get("location")
            if isinstance(location, dict) and isinstance(location.get("dimension"), str):
                found.add(_path(location["dimension"]))
            walk(pred)

    walk(player)
    return found


def _predicate_value(entity_conditions, key: str):
    """Pull ``predicate[key]`` out of a criterion sub-condition, tolerating both the list form
    ``[{"condition": "entity_properties", "predicate": {...}}]`` and a bare ``{...}``."""
    if isinstance(entity_conditions, list):
        for sub in entity_conditions:
            if isinstance(sub, dict):
                pred = sub.get("predicate", sub)
                if isinstance(pred, dict) and key in pred:
                    return pred[key]
    elif isinstance(entity_conditions, dict):
        pred = entity_conditions.get("predicate", entity_conditions)
        if isinstance(pred, dict):
            return pred.get(key)
    return None
