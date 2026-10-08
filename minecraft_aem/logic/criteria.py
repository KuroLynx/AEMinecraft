"""Advancement criteria → logic rule, in three separate steps (the only advancement compiler).

    criterion ──walk──► requirements (plain data) ──price──► Rule

1. **Walk** (``_entity`` / ``_location`` / ``_item`` / ``_damage`` / ``_type_specific``): one reader per
   Minecraft predicate type, used by every trigger. A reader returns a small requirements tree built
   from ``All`` / ``Any`` / ``Strict`` groups and ``Need(kind, *args)`` leaves, or ``None`` when the
   predicate pins nothing. Keys a reader neither uses nor deliberately ignores are counted in
   ``self.unread``, so every unread field is reported in one place.
2. **Triggers** (``TRIGGERS``): data. Each trigger lists which condition key holds which predicate,
   what it needs regardless of its conditions (``implied``), and what an empty criterion means. Only
   a handful of triggers keep a function (``_custom_*``).
3. **Price** (``_price``): each ``Need`` kind maps to one ``RuleHelper`` call. This is where the
   game knowledge and the AP locks live; the walker knows nothing about either.

Lookup tables (block gates, damage tags, effect sources, enchantment loot…) live in tables.py.

Uniform policy:
  * a requirement that can't be priced (unknown item, unmapped stat) voids its whole ``All`` group, so
    the criterion falls back to the parent chain instead of turning silently cheaper;
  * a facet that pins nothing is simply absent (``None`` from the walker).

Not ported (each appears in the comparison report): the armor-stand "Living Dummy" kill, BACAP's
dimension-penetration arrow NBT, and the Hard-difficulty spider-effect waiver.
"""
from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass

from .acquisition import RuleHelper, _acquisition_table, _block_structures
from .ast import Const, Rule, and_, or_
from .constants import K_ARMOR, K_BREWING, K_HOE, K_PICKAXE, MAT_IRON, REGION_END, REGION_NETHER, REGION_OVERWORLD
from .tables import (
    _BLOCK_EXTRA_GATE,
    _BLOCK_GATE,
    _BLOCK_REGION,
    _BLOCK_TRANSPORT,
    _BULK_COUNT,
    _CUSTOM_STAT_ITEM,
    _CUSTOM_STAT_TRIVIAL,
    _DAMAGE_TAG_GATE,
    _DIMENSION_REGION,
    _EFFECT_SOURCE,
    _ENCHANT_LOOT_SOURCE,
    _EQUIPMENT_SLOTS,
    _EXPLOSION_ITEM,
    _EXTRA_REQUIREMENT,
    _FARMLAND_CROPS,
    _NEEDS_A_MOB,
    _NOT_IN_ENCHANTING_TABLE,
    _POTION_ITEMS,
    _PROJECTILE_SHOOTERS,
    _SMITHING_STATION,
    _SPIDER_SPAWN_EFFECTS,
    _LOOT_TABLE_STRUCT,
    _PLANT_ITEM,
    _TRIM_MATERIAL_ITEM,
    _brewing,
    _pinned_dimensions,
    _pins_participant_type,
    _predicate_value,
    _reagent_routes,
    _tags,
)


# ---------------------------------------------------------------------------------------------------
# Requirements tree
# ---------------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Need:
    """One thing the criterion requires. ``kind`` picks the pricer (``_price_<kind>``)."""
    kind: str
    args: tuple = ()


@dataclass(frozen=True)
class All:
    parts: tuple


@dataclass(frozen=True)
class Any:
    parts: tuple


@dataclass(frozen=True)
class Strict:
    """Required by strict logic, waived in the glitch graph (``RuleHelper.strict_only``)."""
    part: object


FREE = Need("free")          # pinned, and costs nothing (a light level, a reachable coordinate)
UNKNOWN = Need("unknown")    # pinned, and we can't price it: voids the group it sits in


def need(kind: str, *args) -> Need:
    return Need(kind, args)


def all_(*parts):
    """AND of the facets that pinned something; ``None`` when none did."""
    kept = []
    for part in parts:
        if isinstance(part, All):
            kept.extend(part.parts)
        elif part is not None:
            kept.append(part)
    if not kept:
        return None
    return kept[0] if len(kept) == 1 else All(tuple(kept))


def any_(*parts):
    """OR of the alternatives that pinned something; ``None`` when none did."""
    kept = [part for part in parts if part is not None]
    if not kept:
        return None
    return kept[0] if len(kept) == 1 else Any(tuple(kept))


def _path(gid) -> str:
    return gid.rsplit(":", 1)[-1] if isinstance(gid, str) else gid


def _ns(gid: str) -> str:
    return gid if ":" in gid else f"minecraft:{gid}"


def _ids(value) -> list:
    """An id, a list of ids, or ``{"blocks": …}`` / ``{"items": …}`` → list of id strings."""
    if isinstance(value, dict):
        value = value.get("blocks", value.get("items"))
    if isinstance(value, str):
        return [value]
    return [v for v in value if isinstance(v, str)] if isinstance(value, list) else []


def _min(value) -> int:
    if isinstance(value, dict):
        value = value.get("min")
    return value if isinstance(value, int) and not isinstance(value, bool) else 0


# ---------------------------------------------------------------------------------------------------
# Trigger table
# ---------------------------------------------------------------------------------------------------
#
# fields   : condition key -> reader. "entity:<role>" reads an entity predicate whose type is priced as
#            <role> (entity / defeat / tame / breed / summon / projectile / explosion / self = type ignored).
# implied  : what the trigger costs whatever its conditions say.
# empty    : what a criterion with no conditions at all costs.
# unpinned : what it costs when it has conditions but none of them pin anything.
# player   : the player predicate alone is a complete answer (the trigger itself demands nothing).
# custom   : name of a _custom_<name> method for what doesn't fit the table.
# ignore   : condition keys that are deliberately not requirements.

def _item(gid):
    return need("item", gid, 1)


def _call(method, *args):
    return need("call", method, *args)


_BOW_AND_ARROW = All((Any((_item("minecraft:bow"), _item("minecraft:crossbow"))),
                      _call("can_get_arrow")))

# Mounts you can only ride once something is put on them (the item is yours to make and hold first).
# Camels/horses mount bare-handed; a nautilus only once tamed (AbstractNautilus.mobInteract checks isTame).
_MOUNT_NEED = {"happy_ghast": need("item_tag", "#minecraft:harnesses"),
               "nautilus": need("item_tag", "#minecraft:nautilus_taming_items"),
               "zombie_nautilus": need("item_tag", "#minecraft:nautilus_taming_items")}
# The most a bare hand deals in one hit (1, or 1.5 as a critical); a "dealt" above it needs a weapon.
_FIST_DAMAGE = 1.5

# Something in hand (inventory_lock "carry"). For triggers that always involve an item even when the
# criterion doesn't name one: eating, using, placing, throwing an item to a mob, interacting with one.
# A named item already carries it through acquire(); this catches the unnamed ones, which otherwise
# read as doable empty-handed — and with every slot locked, could hold the first slot.
HOLD = need("slot", "carry")

TRIGGERS: dict[str, dict] = {
    # The trigger is the whole requirement.
    "slept_in_bed": {"implied": [_item("minecraft:white_bed")]},
    "used_totem": {"implied": [_item("minecraft:totem_of_undying")]},
    "used_ender_eye": {"implied": [_item("minecraft:ender_eye")]},
    "crafter_recipe_crafted": {"implied": [_item("minecraft:crafter")], "ignore": {"recipe_id", "ingredients"}},
    "hero_of_the_village": {"implied": [_call("can_win_raid")]},
    "voluntary_exile": {"implied": [need("entity_id", "minecraft:pillager"), _call("any_village")]},
    "avoid_vibration": {"implied": [need("structure_id", "minecraft:ancient_city")]},
    "nether_travel": {"implied": [need("region", REGION_NETHER)], "ignore": {"start_position", "distance"}},
    "levitation": {"implied": [need("entity_id", "minecraft:shulker")], "ignore": {"distance", "duration"}},
    "fall_from_height": {"implied": [need("region", REGION_OVERWORLD)], "ignore": {"start_position", "distance"}},
    "allay_drop_item_on_block": {"implied": [need("entity_id", "minecraft:allay"), _item("minecraft:note_block")],
                                 "ignore": {"location"}},
    "ride_entity_in_lava": {"implied": [need("entity_id", "minecraft:strider"), need("region", REGION_NETHER)],
                            "ignore": {"start_position", "distance"}},
    "spear_mobs": {"implied": [need("item_tag", "#minecraft:spears")], "ignore": {"count"}},
    "cured_zombie_villager": {"implied": [need("entity_id", "minecraft:zombie_villager"),
                                          _item("minecraft:golden_apple"), _item("minecraft:brewing_stand"),
                                          need("knowledge", K_BREWING), _item("minecraft:fermented_spider_eye")],
                              "ignore": {"villager", "zombie"}},
    "tick": {"empty": FREE, "player": True},
    "location": {"player": True},
    "started_riding": {"player": True},
    "impossible": {},          # granted by a datapack function: nothing to read
    "recipe_unlocked": {},     # fires on picking up an ingredient: nothing to read

    # Items.
    "inventory_changed": {"fields": {"items": "items"}, "player": True, "ignore": {"slots"}},
    "consume_item": {"implied": [HOLD], "fields": {"item": "item"}, "empty": _call("can_get_food")},
    "using_item": {"implied": [HOLD], "fields": {"item": "item"}},
    "shot_crossbow": {"implied": [HOLD], "fields": {"item": "item"}},
    "item_durability_changed": {"fields": {"item": "item"}, "empty": _item("minecraft:shears"),
                                "unpinned": _item("minecraft:shears"), "ignore": {"delta", "durability"}},
    "player_sheared_equipment": {"implied": [_item("minecraft:shears")],   # shearing takes shears
                                 "fields": {"item": "item", "entity": "entity:entity"}},
    "thrown_item_picked_up_by_player": {"implied": [HOLD], "fields": {"item": "item", "entity": "entity:entity"}},
    "thrown_item_picked_up_by_entity": {"implied": [HOLD], "fields": {"item": "item", "entity": "entity:entity"}},
    "filled_bucket": {"custom": "bucket", "ignore": {"item"}},
    "recipe_crafted": {"fields": {"ingredients": "items"}, "custom": "recipe", "ignore": {"recipe_id"}},
    "brewed_potion": {"implied": [need("brew")], "fields": {"potion": "potion_id"}},
    "construct_beacon": {"implied": [_item("minecraft:beacon")], "custom": "beacon", "ignore": {"level"}},
    "enchanted_item": {"implied": [_item("minecraft:enchanting_table")], "fields": {"item": "item"},
                       "ignore": {"levels"}},
    "fishing_rod_hooked": {"implied": [_item("minecraft:fishing_rod")], "fields": {"entity": "entity:entity"},
                           "ignore": {"item", "rod"}},   # the catch is the reward, not a prerequisite
    "target_hit": {"implied": [_item("minecraft:target")], "fields": {"projectile": "entity:projectile"},
                   "ignore": {"signal_strength"}},
    "bee_nest_destroyed": {"implied": [need("entity_id", "minecraft:bee")], "fields": {"item": "item"},
                           "ignore": {"block", "num_bees_inside"}},

    # Mobs.
    "player_killed_entity": {"fields": {"entity": "entity:defeat", "killing_blow": "damage_source"},
                             "empty": _call("can_kill_any_mob")},
    "entity_killed_player": {"fields": {"entity": "entity:entity", "killing_blow": "damage_source"},
                             "empty": _call("can_kill_any_mob"), "custom": "killer"},
    "kill_mob_near_sculk_catalyst": {"implied": [need("structure_id", "minecraft:ancient_city")],
                                     "fields": {"entity": "entity:entity", "killing_blow": "damage_source"}},
    "player_hurt_entity": {"fields": {"entity": "entity:entity", "damage": "damage:self"}},
    "entity_hurt_player": {"fields": {"damage": "damage:entity"}},
    "killed_by_arrow": {"fields": {"victims": "victims", "fired_from_weapon": "item"}, "custom": "arrow",
                        "ignore": {"unique_entity_types"}},
    "channeled_lightning": {"implied": [_item("minecraft:trident")], "fields": {"victims": "entity_list"}},
    "player_interacted_with_entity": {"implied": [HOLD], "fields": {"item": "item", "entity": "entity:entity"},
                                      "custom": "lead"},
    "tame_animal": {"fields": {"entity": "entity:tame"}, "empty": need("any_mob", "tame")},
    "bred_animals": {"fields": {"child": "entity:breed", "parent": "entity:breed", "partner": "entity:breed"},
                     "empty": need("any_mob", "breed")},
    "summoned_entity": {"fields": {"entity": "entity:summon"}},
    "villager_trade": {"fields": {"villager": "entity:self", "item": "trade_output"}, "custom": "trade"},
    "effects_changed": {"fields": {"effects": "effects", "source": "entity:entity"}, "empty": need("brew_stand"),
                        "player": True},
    "fall_after_explosion": {"fields": {"cause": "entity:explosion"}, "custom": "explosion",
                             "ignore": {"start_position", "distance"}},
    "lightning_strike": {"fields": {"bystander": "entity:entity"}, "empty": FREE, "player": True,
                         "ignore": {"lightning"}},

    # Places and blocks.
    "changed_dimension": {"fields": {"to": "dimension:enter", "from": "dimension:region"}},
    "placed_block": {"implied": [HOLD], "fields": {"location": "block_location:place"}},
    "item_used_on_block": {"implied": [HOLD], "fields": {"location": "block_location:use", "item": "item"}},
    "slide_down_block": {"fields": {"block": "block", "blocks": "block"}, "ignore": {"state"}},
    "default_block_use": {"fields": {"location": "block_location:use"}, "custom": "transport"},
    "any_block_use": {"fields": {"location": "block_location:use"}, "custom": "transport"},
    "enter_block": {"fields": {"block": "block", "location": "block_location:use"}, "custom": "transport",
                    "ignore": {"state"}},
    "player_generates_container_loot": {"fields": {"loot_table": "loot_table"}},
}

# Keys each predicate reader understands, and the ones it deliberately skips. Anything else is unread.
_ENTITY_KEYS = {"type", "location", "stepping_on", "effects", "equipment", "vehicle", "passenger",
                "type_specific", "components", "predicates", "nbt"}
_ENTITY_SKIP = {"distance", "flags", "team", "movement", "movement_affected_by", "targeted_entity",
                "slots", "periodic_tick"}
_LOCATION_KEYS = {"position", "biomes", "structures", "dimension", "block"}
_LOCATION_FREE = {"fluid", "light", "smokey", "can_see_sky"}   # a threshold you can just go and stand in
_ITEM_KEYS = {"items", "count", "components", "predicates"}
_COMPONENTS = {"potion_contents", "enchantments", "stored_enchantments", "trim", "container",
               "bundle_contents", "jukebox_playable"}
_TYPE_SPECIFIC_KEYS = {"type", "advancements", "stats", "has_raid", "looking_at"}
_TYPE_SPECIFIC_SKIP = {"gamemode", "level", "recipes", "input", "food"}

# Projectile entities that are fired from a differently named item.
_PROJECTILE_ITEM = {"fishing_bobber": "fishing_rod",
                    "spectral_arrow": "spectral_arrow", "egg": "egg"}

# Blocks with no item of their own, mapped to the item that places them (crop blocks are in
# _PLANT_ITEM; these are the rest a criterion names).
_BLOCK_ITEM = {
    "cave_vines": "minecraft:glow_berries", "cave_vines_plant": "minecraft:glow_berries",
    "kelp_plant": "minecraft:kelp", "tall_seagrass": "minecraft:seagrass",
    "big_dripleaf_stem": "minecraft:big_dripleaf", "twisting_vines_plant": "minecraft:twisting_vines",
    "weeping_vines_plant": "minecraft:weeping_vines",
    "attached_melon_stem": "minecraft:melon_seeds", "attached_pumpkin_stem": "minecraft:pumpkin_seeds",
}

# Blocks whose existence is the whole requirement and that no item or structure explains.
_BLOCK_SOURCE = {
    "frosted_ice": lambda c: c._price_enchant("frost_walker", True),   # Frost Walker boots over water
}

# Effects a mob gives itself, so pinning one on that mob costs the player nothing. Wandering traders drink
# Invisibility at night (Shady Deals).
_SELF_EFFECTS = {"wandering_trader": {"minecraft:invisibility"}}
# Spiders roll one of these on spawn on Hard difficulty. Difficulty is the player's setting, so strict
# logic still prices the potion and the glitch graph waives it.
_DIFFICULTY_EFFECTS = {"spider": _SPIDER_SPAWN_EFFECTS, "cave_spider": _SPIDER_SPAWN_EFFECTS}

# Player equipment slots -> the inventory_lock slot group that has to be open to use them.
_SLOT_GROUP = {"head": "armor", "chest": "armor", "legs": "armor", "feet": "armor", "offhand": "offhand"}

# Coordinate thresholds that stop being "walk there" .
_DIMPEN_REGION = {"overworld": REGION_OVERWORLD, "nether": REGION_NETHER, "end": REGION_END}
_SKY_LIMIT, _NETHER_ROOF, _NETHER_LAVA_SEA, _FAR = 320, 127, 31, 10000
_NETHER_BIOMES = {"nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas"}
_END_BIOMES = {"the_end", "end_highlands", "end_midlands", "end_barrens", "small_end_islands"}


class CriteriaCompiler:
    """Advancement record → Rule: ``compile(record, gid)`` / ``parent_rule(record, gid)``."""

    # Per-advancement rules that REPLACE the compiled one (_EXTRA_REQUIREMENT can only add). Keyed by game_id; the value takes the RuleHelper.
    OVERRIDES: dict = {}

    def __init__(self, helper: RuleHelper, active_locations: frozenset | None = None,
                 records: dict | None = None):
        self.h = helper
        # This player's Minecraft version's mobs, structures, advancements (minecraft_version).
        self.content = helper.content
        self._records = records or {}
        self._active = active_locations
        self._compiling: list[str] = []
        self._entity_by_path = {_path(d.game_id): n for n, d in self.content.MOBS_ALL.items()}
        self._struct_by_path = {_path(d.game_id): n for n, d in self.content.STRUCTURES.items()}
        self._adv_loc_by_gid = {d.game_id: n for n, d in self.content.ADVANCEMENT_LOCATIONS.items()}
        tags = _tags(self.content)
        self._item_tags = tags.get("item", {})
        self._entity_tags = tags.get("entity_type", {})
        self._block_tags = tags.get("block", {})
        self.unread: Counter = Counter()   # "<trigger>.<key path>" -> times seen and not read

    # -- public ----------------------------------------------------------------------------------
    def compile(self, record: dict, gid: str | None = None) -> Rule | None:
        if gid in self.OVERRIDES:
            return self.OVERRIDES[gid](self.h)
        if gid is not None:
            self._compiling.append(gid)
        try:
            return self._price(self.requirements(record, gid))
        finally:
            if gid is not None:
                self._compiling.pop()

    def requirements(self, record: dict, gid: str | None = None):
        """The advancement as a requirements tree (step 1 + 2 only). ``None`` = can't derive it."""
        criteria = record.get("criteria", {})
        groups = []
        for group in record.get("requirements") or [[name] for name in criteria]:
            options = [self.criterion(criteria[name]) for name in group if name in criteria]
            options = [o for o in options if o is not None]
            if not options:
                return None   # an OR-group with nothing readable can't be guaranteed
            groups.append(any_(*options))
        extra = _EXTRA_REQUIREMENT.get(gid) if gid else None
        if extra is not None:
            groups.append(need("rule", extra))
        return all_(*groups)

    def parent_rule(self, record: dict, gid: str | None = None) -> Rule | None:
        parent_gid = record.get("parent")
        loc = self._adv_loc_by_gid.get(parent_gid) if parent_gid else None
        parent = self.h.reached(loc) if loc is not None and (self._active is None or loc in self._active) else None
        extra = _EXTRA_REQUIREMENT.get(gid) if gid else None
        if extra is None:
            return parent
        return extra(self.h) if parent is None else and_(parent, extra(self.h))

    # -- one criterion ---------------------------------------------------------------------------
    def criterion(self, crit: dict):
        trigger = crit.get("trigger") or ""
        trigger = trigger if ":" in trigger else f"minecraft:{trigger}"
        name = _path(trigger)
        spec = TRIGGERS.get(name)
        cond = crit.get("conditions") or {}
        if spec is None:
            self.unread[f"{name}"] += 1   # a whole trigger nobody handles
            return None

        player = self._conditions(cond.get("player"), lambda p: self._entity(p, "self", f"{name}.player"))
        fields = spec.get("fields", {})
        parts = []
        for key, value in cond.items():
            if key in fields:
                parts.append(self._read(fields[key], value, f"{name}.{key}"))
            elif key != "player" and key not in spec.get("ignore", ()):
                self.unread[f"{name}.{key}"] += 1
        custom = spec.get("custom")
        if custom:
            parts.append(getattr(self, f"_custom_{custom}")(cond))

        rule = all_(*parts)
        if rule is None and not cond and "empty" in spec:
            rule = spec["empty"]
        if rule is None and "unpinned" in spec:
            rule = spec["unpinned"]
        if rule is None and spec.get("player"):
            rule = player
            player = None
        # What the trigger needs whatever its conditions say joins AFTER the empty/unpinned fallbacks:
        # those stand in for a criterion that pins nothing, and an implied requirement (an item in
        # hand, say) pinning "something" must not hide that. Husbandry ("eat anything") became just
        # "hold something" when HOLD counted as the criterion's content.
        implied = spec.get("implied", ())
        if implied:
            rule = all_(rule, *implied)
        if rule is None:
            return None
        if trigger in _NEEDS_A_MOB and not _pins_participant_type(cond):
            rule = all_(rule, _call("can_kill_any_mob"))
        return all_(rule, player)

    def _read(self, reader: str, value, path: str):
        kind, _, arg = reader.partition(":")
        if kind == "entity":
            return self._conditions(value, lambda p: self._entity(p, arg, path))
        if kind == "entity_list":
            return all_(*[self._conditions(v, lambda p: self._entity(p, "entity", path)) for v in value or ()])
        if kind == "victims":
            return all_(*[self._conditions(v, lambda p: self._entity(p, "defeat", path)) for v in value or ()])
        if kind == "item":
            return self._item(value, path)
        if kind == "items":
            return all_(*[self._item(v, path) or UNKNOWN for v in value]) if isinstance(value, list) else None
        if kind == "damage":
            return self._damage(value, arg, path)
        if kind == "damage_source":
            return self._damage_source(value, path)
        if kind == "block":
            return any_(*[self._block(b) for b in _ids(value)])
        if kind == "block_location":
            return self._block_conditions(value, arg, path)
        if kind == "dimension":
            region = _DIMENSION_REGION.get(_path(value))
            return need("enter" if arg == "enter" else "region", region) if region else None
        if kind == "potion_id":
            return need("potion", self._potion_name(value)) if isinstance(value, str) else None
        if kind == "loot_table":
            return need("loot_table", value) if isinstance(value, str) else None
        if kind == "trade_output":
            ids = [i for raw in _ids((value or {}).get("items")) for i in self._expand_items(raw)]
            return any_(*[need("take_lock", i) for i in ids])
        if kind == "effects":
            return all_(*[need("effect", e) for e in value]) if isinstance(value, dict) else None
        raise KeyError(reader)

    # -- custom triggers (what doesn't fit the table) ---------------------------------------------
    def _custom_recipe(self, cond):
        if cond.get("ingredients"):
            return None
        rid = cond.get("recipe_id")
        if not isinstance(rid, str):
            return None
        base = _path(rid)
        if base.endswith("_smithing_trim"):
            return All((need("station", _SMITHING_STATION), _item(f"minecraft:{base[:-len('_smithing_trim')]}"),
                        need("item_tag", "#minecraft:trimmable_armor"), need("item_tag", "#minecraft:trim_materials")))
        return need("recipe", rid)

    def _custom_trade(self, cond):
        """A trade happens whatever the villager predicate pins; a Wandering Trader is its own route."""
        trader = _predicate_value(cond.get("villager"), "type")
        wandering = isinstance(trader, str) and _path(trader) == "wandering_trader"
        return _call("meet_wandering_trader" if wandering else "can_trade_villager")

    def _custom_bucket(self, cond):
        """A bucket, plus the mob it scoops up. Priced as the empty bucket at the top level rather than
        acquire("<x>_bucket"): that one level of extra recursion hits acquire's depth limit, whose
        material-tier fallback drops Pickaxe Handling from the iron."""
        ids = _ids((cond.get("item") or {}).get("items"))
        if not ids:
            return None
        mob = self._entity_by_path.get(_path(ids[0]).removesuffix("_bucket"))
        return all_(_item("minecraft:bucket"), need("entity", mob) if mob else None)

    def _custom_killer(self, cond):
        """An armor stand only deals damage once you've built and kitted out an animated dummy
        (BACAP's Living Dummy): the stand, Armor Handling, an enchanting table and iron gear."""
        if _path(_predicate_value(cond.get("entity"), "type")) != "armor_stand":
            return None
        return All((_item("minecraft:armor_stand"), need("knowledge", K_ARMOR),
                    _item("minecraft:enchanting_table"), _call("material", MAT_IRON)))

    def _custom_beacon(self, cond):
        return _call("can_get_beacon_base") if _min(cond.get("level")) >= 1 else None

    def _custom_arrow(self, cond):
        weapon = None if cond.get("fired_from_weapon") else _BOW_AND_ARROW
        piercing = (len(cond.get("victims") or ()) >= 2 or (cond.get("unique_entity_types") or 0) >= 2)
        return all_(weapon, need("enchant", "piercing", True) if piercing else None)

    def _custom_lead(self, cond):
        """"Any entity except …" (an inverted predicate, no type): any leashable mob not excluded."""
        entity = cond.get("entity")
        if not entity or _predicate_value(entity, "type") is not None:
            return None
        excluded = set()
        for sub in entity if isinstance(entity, list) else [entity]:
            term = sub.get("term") if isinstance(sub, dict) and _path(sub.get("condition", "")) == "inverted" else None
            if isinstance(term, dict):
                excluded.update(self._entity_names((term.get("predicate") or term).get("type")))
        mobs = [n for n in self.content.MOBS_LEASHABLE if n not in excluded] or [n for n in self.content.MOBS_ALL if n not in excluded]
        return any_(*[need("entity", n) for n in mobs])

    def _custom_explosion(self, cond):
        cause = _predicate_value(cond.get("cause"), "type")
        if isinstance(cause, str) and not cause.startswith("#"):
            return None   # the `cause` field already priced it
        return Any((_item("minecraft:wind_charge"), _item("minecraft:tnt")))

    def _custom_transport(self, cond):
        """A block somewhere it doesn't generate (powder snow in the Nether) has to be carried there."""
        pinned = {_DIMENSION_REGION[d] for d in _pinned_dimensions(cond.get("player"))
                  if d in _DIMENSION_REGION}
        if not pinned:
            return None
        blocks = _ids(cond.get("block"))
        items = [_BLOCK_TRANSPORT[_path(b)] for b in blocks
                 if _path(b) in _BLOCK_TRANSPORT and not pinned & set(_BLOCK_REGION.get(_path(b), ()))]
        return any_(*[_item(i) for i in items])

    # -- predicate readers -----------------------------------------------------------------------
    def _check(self, pred: dict, known, path: str) -> None:
        for key in pred:
            if key not in known:
                self.unread[f"{path}.{key}"] += 1

    def _conditions(self, value, read):
        """A predicate in any of its spellings: a bare dict, a list (AND) of loot conditions,
        ``any_of`` / ``all_of`` groups, ``entity_properties`` wrappers. ``inverted`` pins nothing."""
        if isinstance(value, list):
            return all_(*[self._conditions(v, read) for v in value])
        if not isinstance(value, dict):
            return None
        condition = _path(value.get("condition", ""))
        if condition == "inverted":
            return None
        if condition in ("any_of", "all_of"):
            parts = [self._conditions(t, read) for t in value.get("terms", ())]
            return any_(*parts) if condition == "any_of" else all_(*parts)
        return read(value.get("predicate", value) if condition in ("", "entity_properties") else {})

    def _entity(self, pred: dict, role: str, path: str):
        if not isinstance(pred, dict):
            return None
        self._check(pred, _ENTITY_KEYS | _ENTITY_SKIP, path)
        equipment = pred.get("equipment") if isinstance(pred.get("equipment"), dict) else {}
        variant = any(str(k).endswith("/variant")
                      for holder in ("components", "predicates") if isinstance(pred.get(holder), dict)
                      for k in pred[holder])
        nbt = pred.get("nbt") if isinstance(pred.get("nbt"), str) else ""
        carried = []
        if nbt.startswith("{Inventory:"):
            carried = [_item(i) for i in sorted(set(re.findall(r'id:"([a-z0-9_:]+)"', nbt)))]
        # BACAP tags an arrow with each dimension it has flown through (Dimension Penetration).
        carried += [need("region", _DIMPEN_REGION[m]) for m in re.findall(r"dimpen_(\w+)", nbt)
                    if m in _DIMPEN_REGION]
        # A variant pins the species when `type` doesn't: "minecraft:parrot/variant" is a parrot.
        species = next((str(k).rsplit(":", 1)[-1].split("/")[0]
                        for holder in ("components", "predicates") if isinstance(pred.get(holder), dict)
                        for k in pred[holder] if str(k).endswith("/variant")), None)
        gid = pred.get("type") or (f"minecraft:{species}" if species else None)
        mount = _MOUNT_NEED.get(_path(gid)) if path.endswith(".vehicle") and isinstance(gid, str) else None
        return all_(
            mount,
            self._entity_type(gid, role),
            self._location(pred.get("location"), f"{path}.location"),
            self._location(pred.get("stepping_on"), f"{path}.stepping_on"),
            *[self._item(v, f"{path}.equipment") or UNKNOWN for k, v in equipment.items() if k in _EQUIPMENT_SLOTS],
            # Wearing / holding it is what needs the slot (inventory_lock) — only on YOU: a raid
            # captain's banner or a zombie's armor is on someone else's.
            *[need("slot", _SLOT_GROUP[k]) for k in equipment if role == "self" and k in _SLOT_GROUP],
            *[self._effect_on(e, gid) for e in (pred.get("effects") or {})],
            self._entity(pred.get("vehicle"), "entity", f"{path}.vehicle"),
            self._entity(pred.get("passenger"), "entity", f"{path}.passenger"),
            self._type_specific(pred.get("type_specific"), f"{path}.type_specific"),
            Strict(need("biome_finder")) if variant else None,
            *carried,
        )

    def _effect_on(self, effect: str, gid):
        """An effect an entity predicate pins on its subject."""
        species = {_path(g) for g in (gid if isinstance(gid, list) else [gid]) if isinstance(g, str)}
        if species and all(effect in _SELF_EFFECTS.get(s, ()) for s in species):
            return None
        if species and all(effect in _DIFFICULTY_EFFECTS.get(s, ()) for s in species):
            return Strict(need("effect", effect))
        return need("effect", effect)

    def _entity_names(self, gid) -> list:
        ids = gid if isinstance(gid, list) else [gid]
        out = []
        for one in ids:
            if not isinstance(one, str):
                continue
            members = self._entity_tags.get(_ns(one[1:]), []) if one.startswith("#") else [one]
            out += [self._entity_by_path[_path(m)] for m in members if _path(m) in self._entity_by_path]
        return out

    def _entity_type(self, gid, role: str):
        """The species an entity predicate pins, priced by the role the criterion gives it."""
        if gid is None or role == "self":
            return None
        ids = [g for g in (gid if isinstance(gid, list) else [gid]) if isinstance(g, str)]
        if role == "projectile":
            return any_(*[need("projectile", _path(g)) for g in ids])
        names = self._entity_names(ids)
        if role == "tame":
            names = [n for n in names if n in self.content.MOBS_TAMEABLE]
        options = [need(role if role != "explosion" else "entity", n) for n in names]
        if not options:
            # A type that is no mob: something you place or throw (glow item frame, boat, tnt). A tag
            # of them (#minecraft:boat) means any member.
            for g in ids:
                members = self._entity_tags.get(_ns(g[1:]), []) if g.startswith("#") else [g]
                options += [_item(_EXPLOSION_ITEM.get(_ns(m), _ns(m))) for m in members]
        return any_(*options)

    def _location(self, loc, path: str):
        if not isinstance(loc, dict):
            return None
        self._check(loc, _LOCATION_KEYS | _LOCATION_FREE, path)
        parts = []
        struct = loc.get("structures")
        if isinstance(struct, str):
            if struct.startswith("#"):
                parts.append(_call("any_village") if "village" in struct else UNKNOWN)
            else:
                parts.append(need("structure_id", struct))
        region = None
        if "biomes" in loc:
            biome = loc["biomes"] if isinstance(loc["biomes"], str) else ""
            region = (REGION_NETHER if _path(biome) in _NETHER_BIOMES
                      else REGION_END if _path(biome) in _END_BIOMES else REGION_OVERWORLD)
            parts.append(Strict(need("biome_finder")))
        dimension = _DIMENSION_REGION.get(_path(loc.get("dimension")))
        region = dimension or region
        if region:
            parts.append(need("region", region))
        if isinstance(loc.get("position"), dict):
            parts.append(self._position(loc["position"], region) or FREE)
        parts += [any_(*[self._block(b) for b in _ids(loc.get("block"))])]
        node = all_(*parts)
        return node if node is not None else (FREE if _LOCATION_FREE & loc.keys() else None)

    def _position(self, position: dict, region):
        def bound(axis, key):
            value = position.get(axis)
            return value.get(key) if isinstance(value, dict) else None

        lo, hi = bound("y", "min"), bound("y", "max")
        parts = []
        if lo is not None and lo >= _SKY_LIMIT:
            parts.append(_call("can_fly"))
        elif region == REGION_NETHER and lo is not None and lo >= _NETHER_ROOF:
            parts.append(_call("can_break_bedrock") if hi is not None and hi < _NETHER_ROOF + 1
                         else _item("minecraft:ender_pearl"))
        if region == REGION_NETHER and hi is not None and lo is None and hi <= _NETHER_LAVA_SEA:
            parts.append(need("knowledge", K_PICKAXE))
        if any(abs(bound(a, k) or 0) >= _FAR for a in ("x", "z") for k in ("min", "max")):
            parts.append(Strict(_call("can_fly")))
        return all_(*parts)

    def _item(self, pred, path: str):
        if not isinstance(pred, dict):
            return None
        self._check(pred, _ITEM_KEYS, path)
        components = {}
        for holder in ("components", "predicates"):
            if isinstance(pred.get(holder), dict):
                for key, value in pred[holder].items():
                    components[_path(key)] = value
        for key in components.keys() - _COMPONENTS:
            if not key.endswith("/variant"):
                self.unread[f"{path}.component.{key}"] += 1

        ids = [i for raw in _ids(pred.get("items")) for i in self._expand_items(raw)]
        potions = self._potion_types(components.get("potion_contents"))
        if potions and any(i in _POTION_ITEMS for i in ids) and all(p in _brewing(self.content) for p in potions):
            base = any_(*[need("potion", p) for p in potions])
        else:
            # No brewing recipe for the type (water, mundane): the item's own sources.
            count = _min(pred.get("count")) or 1
            base = any_(*[need("item", i, count) for i in ids])
            if "water" in potions and "minecraft:potion" in ids:
                base = any_(base, need("potion", "water"))   # or fill a glass bottle yourself

        gates = [base]
        for key, on_item in (("enchantments", True), ("stored_enchantments", False)):
            for entry in self._as_list(components.get(key)):
                names = entry.get("enchantments") if isinstance(entry, dict) else entry
                names = names if isinstance(names, list) else [names]
                gates.append(any_(*[need("enchant", None if n.startswith("#") else _path(n), on_item)
                                    for n in names if isinstance(n, str)]) or need("enchant", None, on_item))
        trim = components.get("trim")
        material = trim.get("material") if isinstance(trim, dict) else None
        if isinstance(material, str) and _path(material) in _TRIM_MATERIAL_ITEM:
            gates.append(All((_item("minecraft:smithing_table"),
                              _item(f"minecraft:{_TRIM_MATERIAL_ITEM[_path(material)]}"))))
        for slot in self._as_list(components.get("container")):
            gid = (slot.get("item") or {}).get("id") if isinstance(slot, dict) else None
            gates.append(_item(_ns(gid)) if isinstance(gid, str) else None)
        bundle = components.get("bundle_contents")
        contains = ((bundle.get("items") or {}).get("contains") if isinstance(bundle, dict) else None) or ()
        gates += [self._item(entry, f"{path}.bundle") or UNKNOWN for entry in contains]
        if "jukebox_playable" in components:
            gates.append(_call("can_get_disc"))
        return all_(*gates)

    def _damage(self, dmg, attacker_role: str, path: str):
        if not isinstance(dmg, dict):
            return None
        self._check(dmg, {"type", "source_entity", "blocked", "dealt", "taken"}, path)
        attacker = self._conditions(dmg.get("source_entity"), lambda p: self._entity(p, attacker_role, path))
        if attacker is not None and attacker_role != "self" and                 _predicate_value(dmg.get("source_entity"), "type") is None:
            attacker = all_(attacker, _call("can_kill_any_mob"))   # pinned by its effects alone: still a mob
        # The damage tag names the PLAYER's weapon only when the player dealt the blow; on a hit taken
        # (entity_hurt_player) it describes what struck you, which costs you nothing.
        parts = [self._damage_source(dmg.get("type"), f"{path}.type", weapon=attacker_role == "self"), attacker]
        dealt = dmg.get("dealt")
        dealt = dealt.get("min") if isinstance(dealt, dict) else dealt
        if attacker_role == "self" and isinstance(dealt, (int, float)) and dealt > _FIST_DAMAGE:
            parts += [HOLD, _call("can_kill")]   # more than a fist deals: a weapon in hand
        if dmg.get("blocked"):
            parts.append(_item("minecraft:shield"))
            if attacker is None:
                parts.append(any_(*[need("entity_id", g) for g in _PROJECTILE_SHOOTERS]))
        return all_(*parts)

    def _damage_source(self, src, path: str, weapon: bool = True):
        """How the blow landed: a projectile, what the attacker wears or holds, else its damage tag."""
        if not isinstance(src, dict):
            return None
        self._check(src, {"tags", "direct_entity", "source_entity", "is_direct"}, path)
        direct = src.get("direct_entity") if isinstance(src.get("direct_entity"), dict) else {}
        source = src.get("source_entity") if isinstance(src.get("source_entity"), dict) else {}
        named = direct.get("type") or direct.get("equipment") or source.get("equipment")
        tags = tuple(t.get("id") for t in src.get("tags") or () if isinstance(t, dict) and t.get("expected", True))
        return all_(self._entity(direct, "projectile", f"{path}.direct_entity") if direct else None,
                    self._entity(source, "self", f"{path}.source_entity") if source else None,
                    need("damage_tag", tags) if weapon and tags and not named else None)

    def _type_specific(self, ts, path: str):
        if not isinstance(ts, dict):
            return None
        self._check(ts, _TYPE_SPECIFIC_KEYS | _TYPE_SPECIFIC_SKIP, path)
        parts = [_call("can_raid") if ts.get("has_raid") is True else None,
                 self._entity(ts.get("looking_at"), "entity", f"{path}.looking_at")]
        for gid, wanted in (ts.get("advancements") or {}).items():
            if wanted is not False:
                parts.append(need("adv", gid))
        for stat in ts.get("stats") or ():
            if not isinstance(stat, dict):
                continue
            kind, sid = _path(stat.get("type")), stat.get("stat")
            if kind == "mined" and isinstance(sid, str):
                parts.append(_item(sid))
            elif kind == "killed" and self._entity_names(sid):
                parts.append(need("defeat", self._entity_names(sid)[0]))
            elif kind == "custom" and sid in _CUSTOM_STAT_ITEM:
                parts.append(_item(_CUSTOM_STAT_ITEM[sid]))
            elif kind == "custom" and sid in _CUSTOM_STAT_TRIVIAL:
                parts.append(FREE)
            else:
                parts.append(UNKNOWN)
        return all_(*parts)

    def _block_conditions(self, value, mode: str, path: str):
        """``placed_block`` / ``item_used_on_block`` / ``*_block_use`` location lists. ``place`` mode
        prices the no-offset block as the item that places it; offset blocks and groups are context."""
        if isinstance(value, list):
            return all_(*[self._block_conditions(v, mode, path) for v in value])
        if not isinstance(value, dict):
            return None
        condition = _path(value.get("condition", ""))
        if condition == "inverted":
            return None
        if condition == "match_tool":
            return self._item(value.get("predicate"), f"{path}.match_tool")
        if condition in ("any_of", "all_of"):
            parts = [self._block_conditions(t, "use", path) for t in value.get("terms", ())]
            return any_(*parts) if condition == "any_of" else all_(*parts)
        if any(k in value for k in ("offsetX", "offsetY", "offsetZ")):
            mode = "use"
        pred = value.get("predicate") if isinstance(value.get("predicate"), dict) else {}
        blocks = _ids(value.get("block")) + _ids(pred.get("block"))
        leaves = any_(*[need("place", b) if mode == "place" else self._block(b) for b in blocks])
        where = self._location({k: v for k, v in pred.items() if k != "block"}, f"{path}.predicate")
        props = value.get("properties") if isinstance(value.get("properties"), dict) else {}
        water = self._block("minecraft:water") if str(props.get("waterlogged", "")).lower() == "true" else None
        return all_(leaves, where, water)

    def _block(self, block: str):
        if block.startswith("#"):
            return any_(*[need("block", m) for m in self._block_tags.get(_ns(block[1:]), ())])
        return need("block", block)

    # -- small helpers ---------------------------------------------------------------------------
    @staticmethod
    def _as_list(value) -> list:
        return value if isinstance(value, list) else ([value] if value is not None else [])

    def _expand_items(self, item_id: str) -> list:
        if item_id.startswith("#"):
            body = _ns(item_id[1:])
            return self._item_tags.get(body) or self._block_tags.get(body, [])
        return [item_id]

    @staticmethod
    def _potion_name(potion_id: str) -> str:
        name = _path(potion_id)
        for prefix in ("long_", "strong_"):
            name = name.removeprefix(prefix)
        return name

    def _potion_types(self, contents) -> list:
        if isinstance(contents, dict):
            contents = contents.get("potion")
        return [self._potion_name(p) for p in self._as_list(contents) if isinstance(p, str)]

    # The lookup tables imported from tables.py call back into the compiler with these names.
    def _entity_gid(self, gid):
        name = self._entity_by_path.get(_path(gid))
        return self.h.entity(name) if name in self.content.MOBS_ALL else None

    def _struct_gid(self, gid):
        name = self._struct_by_path.get(_path(gid))
        return self.h.structure(name) if name in self.content.STRUCTURES else None

    @staticmethod
    def _any_opt(*nodes):
        present = [n for n in nodes if n is not None]
        return or_(*present) if present else None

    @staticmethod
    def _all_req(*nodes):
        return None if any(n is None for n in nodes) else and_(*nodes)

    # -- pricing: requirements tree → Rule ------------------------------------------------------
    def _price(self, node) -> Rule | None:
        if node is None:
            return None
        if isinstance(node, All):
            parts = [self._price(p) for p in node.parts]
            return None if any(p is None for p in parts) else and_(*parts)
        if isinstance(node, Any):
            return self._any_opt(*[self._price(p) for p in node.parts])
        if isinstance(node, Strict):
            part = self._price(node.part)
            return None if part is None else self.h.strict_only(part)
        return getattr(self, f"_price_{node.kind}")(*node.args)

    def _price_free(self):
        return Const(True)

    def _price_unknown(self):
        return None

    def _price_rule(self, build):
        return build(self.h)

    def _price_call(self, method, *args):
        return getattr(self.h, method)(*args)

    def _price_item(self, gid, count=1):
        with self.h.bulk_mode(count >= _BULK_COUNT):
            return self.h.acquire(gid)

    def _price_item_tag(self, tag):
        return self._any_opt(*[self.h.acquire(i) for i in self._expand_items(tag)])

    def _price_slot(self, group):
        return self.h.slot_group(group)

    def _price_knowledge(self, name):
        return self.h.knowledge(name)

    def _price_take_lock(self, gid):
        return self.h.take_lock(gid, "station")

    def _price_station(self, station):
        return self.h._station_node(station)

    def _price_entity(self, name):
        return self.h.entity(name)

    def _price_entity_id(self, gid):
        return self._entity_gid(gid)

    def _price_defeat(self, name):
        return self.h.can_defeat(name)

    def _price_tame(self, name):
        return self.h.can_tame(name)

    def _price_breed(self, name):
        return self.h.can_breed(name)

    def _price_summon(self, name):
        return self.h.summon(name)

    def _price_any_mob(self, how):
        mobs, build = (self.content.MOBS_TAMEABLE, self.h.can_tame) if how == "tame" else (self.content.MOBS_BREEDABLE, self.h.can_breed)
        return self._any_opt(*[build(n) for n in mobs])

    def _price_structure_id(self, gid):
        return self._struct_gid(gid)

    def _price_region(self, region):
        return self.h.access_region(region)

    def _price_enter(self, region):
        return self.h.enter_dimension(region)

    def _price_biome_finder(self):
        return self.h.needs_biome_finder()

    def _price_block(self, block):
        """Being at a block: an authoritative gate, else the dimension it pins, else obtaining it."""
        key = "candle_cake" if _path(block).endswith("candle_cake") else _path(block)
        if key in _BLOCK_GATE:
            return _BLOCK_GATE[key](self.h)
        if key in _BLOCK_REGION:
            extra = _BLOCK_EXTRA_GATE.get(key)
            region = self._any_opt(*[self.h.access_region(r) if extra is None
                                     else and_(self.h.access_region(r), extra(self.h))
                                     for r in _BLOCK_REGION[key]])
            # The dimension is not the whole story for a block that grows only in rare biomes (sweet
            # berry bushes in taigas, powder snow on snowy slopes): standing at one is finding that
            # biome, or a structure that places it — unless you place it yourself.
            placed = self._price_place(block)
            found = self.h._natural_origin(key)
            return and_(region, found if placed is None else or_(found, placed))
        if key in _BLOCK_SOURCE:
            return _BLOCK_SOURCE[key](self)
        node = self.h.acquire(block) or self._price_place(block)
        if node is None and key.startswith("potted_"):
            plant = key.removeprefix("potted_")
            plant_node = self.h.acquire(f"minecraft:{plant}") or self.h.acquire(f"minecraft:{plant.removesuffix('_bush')}")
            node = self._all_req(self.h.acquire("minecraft:flower_pot"), plant_node)
        if node is None:
            # Only ever placed by worldgen (a vault): reach a structure whose template has one.
            node = self._any_opt(*[self.h.structure(s) for s in _block_structures(self.content).get(key, ())
                                   if s in self.h.active_structures])
        return node

    def _price_place(self, block):
        """Placing a block: the item that places it (a crop's seed, flint and steel for fire), and
        farmland — a hoe — for the crops that need it."""
        item = _PLANT_ITEM.get(_ns(block)) or _BLOCK_ITEM.get(_path(block), _ns(block))
        node = self._any_opt(*[self.h.acquire(i) for i in self._as_list(item)])
        if node is not None and _ns(block) in _FARMLAND_CROPS:
            node = and_(node, self.h.knowledge(K_HOE))
        return node

    def _price_effect(self, effect):
        source = _EFFECT_SOURCE.get(effect)
        node = source(self) if source is not None else None
        return node if node is not None else self._price_brew_stand()

    def _price_brew_stand(self):
        return self.h.all_of(self.h.acquire("minecraft:brewing_stand"), self.h.knowledge(K_BREWING))

    def _price_brew(self):
        return self.h.all_of(self.h.acquire("minecraft:brewing_stand"), self.h.acquire("minecraft:blaze_powder"),
                             self.h.knowledge(K_BREWING), self.h.acquire("minecraft:glass_bottle"))

    def _price_potion(self, name):
        if name == "water":   # a glass bottle dipped in water: no brewing at all
            return self._all_req(self.h.acquire("minecraft:glass_bottle"), self.h.access_region(REGION_OVERWORLD))
        routes = _reagent_routes(self.content, name)
        if not routes or not all(routes):
            return self._price_brew()   # mundane / thick / an unknown type: at least a brew
        # 26.3+ tables can list several equally short chains (slowness off swiftness OR leaping).
        priced = [self._all_req(self._price_brew(), *[self.h.acquire(f"minecraft:{r}") for r in route])
                  for route in routes]
        priced = [node for node in priced if node is not None]
        return or_(*priced) if priced else None

    def _price_enchant(self, name, on_item):
        # An enchantment is on something you hold: a book or the enchanted item.
        rule = self._price_enchant_source(name, on_item)
        return None if rule is None else and_(self.h.slot_group("carry"), rule)

    def _price_enchant_source(self, name, on_item):
        anvil = self.h.acquire("minecraft:anvil")
        book = self.h.acquire("minecraft:enchanted_book")
        if name in _ENCHANT_LOOT_SOURCE:
            found = _ENCHANT_LOOT_SOURCE[name](self)
            return found if found is None or not on_item else self._all_req(found, anvil)
        routes = []
        if name is None or name not in _NOT_IN_ENCHANTING_TABLE:
            routes.append(self.h.acquire("minecraft:enchanting_table"))
        if book is not None:
            routes.append(self._all_req(book, anvil) if on_item else book)
        return self._any_opt(*routes)

    def _price_projectile(self, proj):
        if "trident" in proj:
            return self.h.can_get_trident()
        if "arrow" in proj:
            return self._price(_BOW_AND_ARROW)
        if "area_effect_cloud" in proj or proj.endswith("lingering_potion"):
            return self.h.acquire("minecraft:lingering_potion")
        item = _PROJECTILE_ITEM.get(proj, proj)
        if item not in _acquisition_table(self.content):
            return Const(True)   # not an item (the player's own blow, a ghast's fireball): the mob gates it
        return self.h.acquire(_ns(item))

    def _price_damage_tag(self, tags):
        for tag_id, build in _DAMAGE_TAG_GATE.items():
            if tag_id in tags:
                node = build(self)
                if node is not None:
                    return node
        return Const(True)   # a tag that implies no particular weapon

    def _price_adv(self, gid):
        if self._is_back_reference(gid):
            return None
        loc = self._adv_loc_by_gid.get(gid)
        if loc is not None and (self._active is None or loc in self._active):
            return self.h.reached(loc)
        record = self._records.get(gid)
        if record is None or gid in self._compiling:
            return None
        return self.compile(record, gid)

    def _price_recipe(self, rid):
        obtain = self.h.acquire(rid)
        recipes = _acquisition_table(self.content).get(_path(rid), {}).get("recipes")
        if obtain is None or not recipes:
            return obtain
        made = self._any_opt(*[self.h._recipe_node(r) for r in recipes])
        return self._all_req(made, obtain)

    def _price_loot_table(self, table):
        segments = _path(table).split("/")
        for seg in reversed(segments):
            node = self._struct_gid(_LOOT_TABLE_STRUCT.get(seg, seg))
            if node is not None:
                return node
        head = segments[-1].split("_")[0]
        match = next((p for p in self._struct_by_path if p.split("_")[0] == head), None)
        return self._struct_gid(match) if match else None

    def _is_back_reference(self, gid: str) -> bool:
        """Would depending on ``gid`` close a loop back onto an advancement being compiled?

        BACAP lights a tab root up from its own tree: `challenges/root` is granted by any ONE of 47
        criteria, 39 of them a `type_specific.advancements` predicate naming one of its own children.
        Compiling those into reached() gives the access graph back-edges, and AP's can_reach_location
        has no re-entry guard, so the first sweep recursed until the stack blew (RecursionError on
        every `blazeandcave` + `challenge_sanity: all` seed). An ancestor of ``gid`` being compiled
        means ``gid`` is inside that advancement's own subtree: the back-edge."""
        in_progress, seen, current = set(self._compiling), set(), gid
        while current is not None and current not in seen:
            if current in in_progress:
                return True
            seen.add(current)
            record = self._records.get(current)
            current = record.get("parent") if record else None
        return False
