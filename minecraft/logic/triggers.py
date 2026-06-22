"""Compile an advancement's Minecraft criteria into a logic-rule AST.

This is the engine that lets the apworld derive reachability for advancements nobody hand-authored
a rule for — the mass-import / mod / datapack case. It reads the normalised manifest record
(``{parent, requirements, criteria:{name:{trigger, conditions}}}`` — see tools/extract_manifest.py)
and produces an AST :class:`~..logic.ast.Rule`, reusing :class:`RuleHelper` (acquisition.py) as the
"how do I obtain / reach X" knowledge base.

It is deliberately *partial*: Minecraft has ~50 trigger types, many of them action-based
(``used_totem``, ``slept_in_bed``, ``target_hit``) whose real prerequisites are domain knowledge,
not present in the criterion. For anything it can't interpret with confidence, :meth:`compile`
returns ``None`` and the caller falls back (curated override, else parent-chain + region). Leaning
strict-and-fall-back never invents an over-permissive path that could soft-lock generation.

``requirements`` is Minecraft's CNF — a list (AND) of groups (OR) of criterion names — so it maps
straight onto ``and_`` / ``or_``.
"""
from __future__ import annotations

import json
import re
from importlib.resources import files

from ..data import (
    ADVANCEMENT_LOCATIONS,
    MOBS_ALL,
    MOBS_BREEDABLE,
    MOBS_TAMEABLE,
    STRUCTURES,
)
from .acquisition import RuleHelper
from .ast import Rule, and_, or_
from .constants import (
    BACAP_PACK,
    K_BREWING,
    REGION_END,
    REGION_NETHER,
    REGION_OVERWORLD,
    VANILLA_PACK,
)

# Triggers that imply a specific tool/block the criterion never names: hitting a target block is
# gated by crafting one (redstone + hay), brewing by a brewing stand, etc. Reaching the implied item
# is the meaningful gate, so the advancement inherits its acquisition logic.
_IMPLIED_ITEM = {
    "minecraft:target_hit": "minecraft:target",
    "minecraft:brewed_potion": "minecraft:brewing_stand",
    "minecraft:enchanted_item": "minecraft:enchanting_table",
    "minecraft:fishing_rod_hooked": "minecraft:fishing_rod",
}

# Items that carry a brewed potion (and so gate on the brewing chain, not loot).
_POTION_ITEMS = {
    "minecraft:potion", "minecraft:splash_potion",
    "minecraft:lingering_potion", "minecraft:tipped_arrow",
}

_TAGS: dict | None = None
_BREWING: dict | None = None


def _pack_json(filename: str, pack: str = VANILLA_PACK) -> dict:
    root = __package__.rsplit(".", 1)[0]  # e.g. "worlds.minecraft"
    with files(root).joinpath("packs", pack, filename).open(encoding="utf-8") as f:
        return json.load(f)


def _tags() -> dict:
    """Lazily-loaded item + entity-type tag table (tools/build_tags.py), keyed by tag id.

    Vanilla plus any optional pack (BACAP) merged in, so BACAP criteria that reference
    ``#blazeandcave:*`` tags (e.g. ``time_to_mine`` → ``#blazeandcave:pickaxes``) resolve instead of
    falling back to the parent chain. Tag namespaces don't collide (``minecraft:`` vs
    ``blazeandcave:``), so a per-registry dict merge is safe; absent pack tags are ignored."""
    global _TAGS
    if _TAGS is None:
        merged = _pack_json("tags.json")
        try:
            extra = _pack_json("tags.json", pack=BACAP_PACK)
        except (FileNotFoundError, OSError):
            extra = {}
        for registry, tags in extra.items():
            merged.setdefault(registry, {}).update(tags)
        _TAGS = merged
    return _TAGS


def _brewing() -> dict:
    """Lazily-loaded potion-type -> reagent items table (packs/.../brewing.json)."""
    global _BREWING
    if _BREWING is None:
        _BREWING = _pack_json("brewing.json")
    return _BREWING

# Minecraft dimension id -> our region name.
_DIMENSION_REGION = {
    "minecraft:overworld": REGION_OVERWORLD,
    "minecraft:the_nether": REGION_NETHER,
    "minecraft:the_end": REGION_END,
}

# "Reaching / interacting with an entity" triggers: the criterion names an entity type and the
# rule is simply that the entity is reachable. (Killing it bare-handed is always possible; bosses
# carry their own curated kill rule, so these stay reachability-only here.)
_ENTITY_REACH_TRIGGERS = frozenset({
    "minecraft:player_killed_entity",
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


class TriggerCompiler:
    """Compiles a manifest record into a :class:`Rule`, or ``None`` if not confidently derivable."""

    def __init__(self, helper: RuleHelper, active_locations: frozenset | None = None):
        self.h = helper
        # Reverse lookups from Minecraft id -> our display-name key.
        self._entity_by_gid = {data.game_id: name for name, data in MOBS_ALL.items()}
        self._struct_by_gid = {data.game_id: name for name, data in STRUCTURES.items()}
        self._adv_loc_by_gid = {data.game_id: name for name, data in ADVANCEMENT_LOCATIONS.items()}
        self._item_tags = _tags().get("item", {})
        self._entity_tags = _tags().get("entity_type", {})
        # Location names created this seed; a parent-chain rule must not reference a parent that was
        # filtered out (e.g. a challenge advancement when challenge_sanity is off) — that would make
        # AP's reachability sweep raise on an unknown location. None = don't restrict.
        self._active = active_locations

    # -- public -------------------------------------------------------------
    def compile(self, record: dict) -> Rule | None:
        """AST for ``record``'s requirements, or ``None`` if any AND-group is uninterpretable."""
        criteria = record.get("criteria", {})
        # Minecraft's default when `requirements` is absent/empty is "all criteria required" — each
        # criterion as its own AND-group (AdvancementRequirements.allOf). Datapacks (BACAP) usually
        # omit requirements and rely on this; the re-encoded vanilla jar always writes them out. Treat
        # empty-but-has-criteria as that default instead of "uninterpretable" (which dropped ~1159
        # BACAP advancements to the parent chain, ungating their real criteria).
        requirements = record.get("requirements") or [[name] for name in criteria]
        groups: list[Rule] = []
        for group in requirements:  # outer AND
            options: list[Rule] = []
            for crit_name in group:  # inner OR
                crit = criteria.get(crit_name)
                node = self._criterion(crit) if crit else None
                if node is not None:
                    options.append(node)
            # An OR-group with no interpretable option can't be guaranteed → fall back entirely.
            if not options:
                return None
            groups.append(or_(*options))
        if not groups:
            return None
        return and_(*groups)

    def parent_rule(self, record: dict) -> Rule | None:
        """Fallback logic: reach the parent advancement (datapack trees are parent-rooted). Returns
        ``None`` for roots / parents that aren't AP advancement locations, leaving the caller to
        default to always-reachable (region reachability still gates it elsewhere)."""
        parent_gid = record.get("parent")
        loc = self._adv_loc_by_gid.get(parent_gid) if parent_gid else None
        if loc is None or (self._active is not None and loc not in self._active):
            return None
        return self.h.reached(loc)

    # -- per-criterion dispatch --------------------------------------------
    def _criterion(self, crit: dict) -> Rule | None:
        trigger = crit.get("trigger")
        cond = crit.get("conditions") or {}
        # Minecraft treats an unnamespaced trigger as `minecraft:` (BACAP writes some criteria as
        # bare `consume_item` / `inventory_changed`), so normalise before dispatch.
        if isinstance(trigger, str) and ":" not in trigger:
            trigger = f"minecraft:{trigger}"

        if trigger in _ENTITY_REACH_TRIGGERS:
            return self._entity_node(cond)
        if trigger == "minecraft:summoned_entity":
            # Summoning means *building* the entity (an Iron Golem from blocks + a carved pumpkin),
            # not merely encountering one — a naturally spawned village golem does not count. Gate on
            # the build recipe (RuleHelper.summon), OR over the entity type(s) the criterion pins.
            options = [self.h.summon(name) for name in self._entity_names(cond)]
            return or_(*options) if options else None
        if trigger == "minecraft:player_hurt_entity":
            return self._player_hurt_node(cond)
        if trigger == "minecraft:entity_hurt_player":
            return self._entity_hurt_player_node(cond)
        if trigger == "minecraft:killed_by_arrow":
            return self._killed_by_arrow_node(cond)
        if trigger == "minecraft:player_interacted_with_entity":
            # Right-click an entity with an item (lead a mob, feed it, …): need the item AND a valid
            # target entity. A concrete type pins it; an inverted predicate ("any entity except the
            # listed vehicles/non-mobs", e.g. Lead the Way!) means "any mob", so require at least one
            # mob from the registry minus whatever the criterion excludes — both fully data-driven.
            item = self._item_predicate(cond.get("item"))
            entity = self._entity_node(cond)
            if entity is None and cond.get("entity"):
                excluded = set(self._excluded_entity_names(cond))
                entity = self._any_mob([n for n in MOBS_ALL if n not in excluded], self.h.entity)
            parts = [n for n in (item, entity) if n is not None]
            return and_(*parts) if parts else None
        if trigger == "minecraft:tame_animal":
            # The tamed species is pinned on the `entity` predicate as a concrete type or a #tag
            # (#blazeandcave:llamas); empty conditions mean "tame any tameable animal".
            names = self._entity_names(cond)
            if not names:
                species = self._species_from_components(cond)
                names = [species] if species else []
            options = [self.h.can_tame(n) for n in names if n in MOBS_TAMEABLE]
            if options:
                return or_(*options)
            return self._any_mob(MOBS_TAMEABLE, self.h.can_tame) if not cond else None
        if trigger == "minecraft:bred_animals":
            # The bred species is pinned on the `child` predicate (e.g. bred_all_animals); empty
            # conditions mean "breed any animal".
            gid = self._predicate_value(cond.get("child"), "type")
            name = self._entity_by_gid.get(self._ns(gid)) if isinstance(gid, str) else None
            if name:
                return self.h.can_breed(name)
            return self._any_mob(MOBS_BREEDABLE, self.h.can_breed) if not cond else None
        if trigger == "minecraft:changed_dimension":
            region = _DIMENSION_REGION.get(cond.get("to"))
            return self.h.access_region(region) if region else None
        if trigger == "minecraft:location":
            return self._location_node(cond)
        if trigger == "minecraft:inventory_changed":
            return self._inventory_node(cond)
        if trigger == "minecraft:consume_item":
            # "Eat any item" (empty conditions) → any food source; else resolve the specific item.
            return self.h.can_get_food() if not cond else self._item_predicate(cond.get("item"))
        if trigger in ("minecraft:using_item", "minecraft:shot_crossbow"):
            return self._used_item_node(cond)
        if trigger == "minecraft:placed_block":
            return self._placed_block_node(cond)
        if trigger == "minecraft:slide_down_block":
            # Slide down a block (e.g. honey) → you must be able to obtain that block.
            return self._any_acquire(cond.get("blocks"))
        if trigger == "minecraft:item_used_on_block":
            return self._used_on_block_node(cond)
        if trigger == "minecraft:filled_bucket":
            return self._filled_bucket_node(cond)
        if trigger == "minecraft:recipe_crafted":
            ingredients = cond.get("ingredients")
            if ingredients:
                return self._all_items(ingredients)
            return self._recipe_id_node(cond.get("recipe_id"))
        if trigger == "minecraft:construct_beacon":
            node = self.h.acquire("minecraft:beacon")  # nether star (Wither) + glass + obsidian
            if node is not None and self._min_level(cond.get("level")) >= 1:
                # A pyramid is required → also need a base material (any beacon-base block).
                node = self._all_req(node, self.h.can_get_beacon_base())
            return node
        if trigger == "minecraft:villager_trade":
            return self.h.can_trade_villager()
        if trigger == "minecraft:slept_in_bed":
            return self.h.access_region(REGION_OVERWORLD)  # a bed needs wool + planks (overworld)
        if trigger == "minecraft:used_totem":
            return self.h.acquire("minecraft:totem_of_undying")
        if trigger == "minecraft:player_generates_container_loot":
            return self._container_loot_node(cond)
        if trigger in ("minecraft:default_block_use", "minecraft:any_block_use",
                       "minecraft:enter_block"):
            # Use / stand in a block → obtain it if it is craftable/obtainable; a non-obtainable
            # natural block (e.g. an end gateway) carries no item gate, so it reduces to its
            # advancement's own region placement (and_() is trivially true; the export region-gates it).
            node = self._any_acquire(self._blocks_in(cond))
            return node if node is not None else and_()
        if trigger in ("minecraft:item_durability_changed", "minecraft:player_sheared_equipment"):
            # Wear an item down / shear with one → obtain that item (shears for shearing).
            item = self._item_predicate(cond.get("item"))
            return item if item is not None else self.h.acquire("minecraft:shears")
        if trigger in ("minecraft:thrown_item_picked_up_by_player",
                       "minecraft:thrown_item_picked_up_by_entity"):
            # An item was tossed and picked up → obtain that item. The *_by_entity form pins a
            # specific pickerupper on `entity` (a piglin for Oh Shiny), so require reaching it too —
            # the gold item alone must not put Oh Shiny in logic without the Nether/piglin.
            item = self._item_predicate(cond.get("item"))
            entity = self._entity_node(cond) if trigger.endswith("by_entity") else None
            parts = [n for n in (item, entity) if n is not None]
            return and_(*parts) if parts else None
        if trigger == "minecraft:crafter_recipe_crafted":
            # Auto-craft via a Crafter → build a Crafter (its ingredients gate it further).
            return self.h.acquire("minecraft:crafter")
        if trigger == "minecraft:cured_zombie_villager":
            return self._cure_zombie_node()
        if trigger == "minecraft:kill_mob_near_sculk_catalyst":
            return self._sculk_kill_node(cond)
        if trigger == "minecraft:bee_nest_destroyed":
            return self._entity_gid("minecraft:bee")
        if trigger == "minecraft:allay_drop_item_on_block":
            return self._all_req(self._entity_gid("minecraft:allay"),
                                 self.h.acquire("minecraft:note_block"))
        if trigger == "minecraft:ride_entity_in_lava":
            return self._all_req(self._entity_gid("minecraft:strider"),
                                 self.h.access_region(REGION_NETHER))
        if trigger == "minecraft:started_riding":
            return self._started_riding_node(cond)
        if trigger in ("minecraft:voluntary_exile", "minecraft:hero_of_the_village"):
            # A raid: trigger / win it → reach a Pillager and a village.
            return self._all_req(self._entity_gid("minecraft:pillager"), self.h.any_village())
        if trigger == "minecraft:avoid_vibration":
            # Sneak past a sculk sensor → the Deep Dark (Ancient City).
            return self._struct_gid("minecraft:ancient_city")
        if trigger in ("minecraft:channeled_lightning", "minecraft:lightning_strike",
                       "minecraft:spear_mobs"):
            # Channel lightning with a trident / spear mobs with one → obtain a trident.
            return self.h.acquire("minecraft:trident")
        if trigger == "minecraft:nether_travel":
            return self.h.access_region(REGION_NETHER)
        if trigger == "minecraft:levitation":
            # Levitate (Shulker bullets) → reach a Shulker (End City).
            return self._entity_gid("minecraft:shulker")
        if trigger == "minecraft:fall_after_explosion":
            # Be launched by an explosion → a wind charge (Breeze) or TNT.
            return self._any_opt(self.h.acquire("minecraft:wind_charge"),
                                 self.h.acquire("minecraft:tnt"))
        if trigger == "minecraft:fall_from_height":
            return self.h.access_region(REGION_OVERWORLD)  # mountains / high builds
        if trigger == "minecraft:effects_changed":
            return self._effects_node(cond)
        if trigger == "minecraft:used_ender_eye":
            # Throw an Eye of Ender (locate a stronghold) → obtain one (blaze powder + ender pearl).
            return self.h.acquire("minecraft:ender_eye")
        if trigger == "minecraft:tick":
            # Fires every tick: an empty criterion is trivially met (its advancement's region
            # placement still gates it); a populated one pins the requirement via a player predicate,
            # so interpret a location predicate as the `location` trigger does, else fall back.
            return and_() if not cond else self._location_node(cond)
        if trigger in ("minecraft:impossible", "minecraft:recipe_unlocked"):
            # impossible: granted by the datapack's own scoreboard logic, never by gameplay.
            # recipe_unlocked: fires when a recipe is unlocked (usually on picking up an ingredient).
            # Neither's real prerequisite is in the criterion, so defer to the parent-chain fallback.
            return None
        if trigger in _IMPLIED_ITEM:
            return self.h.acquire(_IMPLIED_ITEM[trigger])
        return None

    # -- condition extractors ----------------------------------------------
    @staticmethod
    def _ns(gid: str) -> str:
        """Normalise an id/tag-body to the ``minecraft:`` namespace when it has none — BACAP writes
        many entity types/tags bare (``turtle``, ``#raiders``), and the registries are namespaced."""
        return gid if ":" in gid else f"minecraft:{gid}"

    def _entity_names(self, cond: dict) -> list:
        """Display names for the entity/entities a criterion's `entity` predicate pins via `type` —
        a single id or a ``#tag`` (``#raiders``, ``#blazeandcave:llamas``), with bare ids/tags
        normalised to ``minecraft:`` so the namespaced registries resolve them."""
        gid = self._predicate_value(cond.get("entity"), "type")
        if not isinstance(gid, str):
            return []
        members = (self._entity_tags.get(self._ns(gid[1:]), [])
                   if gid.startswith("#") else [self._ns(gid)])
        return [self._entity_by_gid[m] for m in members if m in self._entity_by_gid]

    def _entity_node(self, cond: dict) -> Rule | None:
        """Reach the entity/entities a criterion's `entity` predicate pins (single id or ``#tag``)."""
        options = [self.h.entity(name) for name in self._entity_names(cond)]
        return or_(*options) if options else None

    def _excluded_entity_names(self, cond: dict) -> list:
        """Display names an INVERTED `entity` predicate excludes (Lead the Way!'s vehicle/non-mob
        list), resolved through the same namespaced registry/tags as ``_entity_names`` — so an
        'any entity except X' constraint stays data-driven instead of assuming what X is."""
        entries = cond.get("entity")
        entries = entries if isinstance(entries, list) else [entries]
        names = []
        for sub in entries:
            if not isinstance(sub, dict) or not str(sub.get("condition", "")).endswith("inverted"):
                continue
            term = sub.get("term")
            if isinstance(term, dict):
                names.extend(self._entity_names({"entity": term.get("predicate", term)}))
        return names

    @staticmethod
    def _min_level(level) -> int:
        """The minimum value a ``construct_beacon`` ``level`` condition demands — an exact int, the
        ``min`` of a bounds object, or 0 when unconstrained (a level-0 beacon needs no pyramid)."""
        if isinstance(level, bool):
            return 0
        if isinstance(level, (int, float)):
            return int(level)
        if isinstance(level, dict):
            lo = level.get("min")
            return int(lo) if isinstance(lo, (int, float)) and not isinstance(lo, bool) else 0
        return 0

    def _weapon_from_projectile(self, cond: dict) -> Rule | None:
        """The launcher a damage criterion pins via its projectile (``damage.type.direct_entity``):
        a trident, or a bow/crossbow + arrow. ``None`` for melee / unpinned damage."""
        dtype = (cond.get("damage") or {}).get("type") or {}
        direct = dtype.get("direct_entity")
        proj = direct.get("type") if isinstance(direct, dict) else None
        if not isinstance(proj, str):
            return None
        if "trident" in proj:
            return self.h.can_get_trident()
        if "arrow" in proj:
            return self._all_req(
                self._any_opt(self.h.acquire("minecraft:bow"), self.h.acquire("minecraft:crossbow")),
                self.h.can_get_arrow(),
            )
        return None

    def _player_hurt_node(self, cond: dict) -> Rule | None:
        """``player_hurt_entity``: the player damages an entity. Require the pinned weapon (trident /
        bow|crossbow + arrow) AND, when a specific victim is pinned, reaching it. An unpinned victim is
        any mob (trivially reachable), so the weapon is the real gate."""
        weapon = self._weapon_from_projectile(cond)
        victim = self._entity_node(cond)
        parts = [n for n in (weapon, victim) if n is not None]
        return and_(*parts) if parts else None

    def _entity_hurt_player_node(self, cond: dict) -> Rule | None:
        """``entity_hurt_player``: the player takes damage. Deflecting a blocked projectile (the
        criterion names no attacker) needs a shield AND any projectile-shooting mob; otherwise reach
        the pinned attacker."""
        damage = cond.get("damage") or {}
        tags = (damage.get("type") or {}).get("tags") or []
        is_projectile = any(isinstance(t, dict) and t.get("id") == "minecraft:is_projectile"
                            and t.get("expected", True) for t in tags)
        if damage.get("blocked") and is_projectile:
            shooter = self._any_opt(*[self._entity_gid(gid) for gid in _PROJECTILE_SHOOTERS])
            return self._all_req(self.h.acquire("minecraft:shield"), shooter)
        return self._entity_node(cond)

    def _killed_by_arrow_node(self, cond: dict) -> Rule | None:
        """``killed_by_arrow``: kill with an arrow/projectile. The victim(s) are on ``victims`` (an AND
        of OR-groups), not ``entity``; the launcher is on ``fired_from_weapon``. Require the weapon
        (the pinned one, else any bow/crossbow + arrow) AND defeating each pinned victim group."""
        weapon = self._any_acquire((cond.get("fired_from_weapon") or {}).get("items"))
        if weapon is None:
            weapon = self._all_req(
                self._any_opt(self.h.acquire("minecraft:bow"), self.h.acquire("minecraft:crossbow")),
                self.h.can_get_arrow(),
            )
        parts = [weapon] if weapon is not None else []
        for group in cond.get("victims") or []:
            options = [self.h.can_defeat(name) for name in self._victim_names(group)]
            if options:
                parts.append(or_(*options))
        return and_(*parts) if parts else None

    def _victim_names(self, group) -> list:
        """Entity display names a ``killed_by_arrow`` ``victims`` OR-group pins (each entry an
        ``entity_properties`` condition whose ``predicate.type`` is an id or a ``#tag``)."""
        entries = group if isinstance(group, list) else [group]
        names = []
        for sub in entries:
            gid = (sub.get("predicate") or {}).get("type") if isinstance(sub, dict) else None
            if not isinstance(gid, str):
                continue
            members = (self._entity_tags.get(self._ns(gid[1:]), [])
                       if gid.startswith("#") else [self._ns(gid)])
            names.extend(self._entity_by_gid[m] for m in members if m in self._entity_by_gid)
        return names

    def _recipe_id_node(self, recipe_id) -> Rule | None:
        """``recipe_crafted`` with no ``ingredients`` (only a ``recipe_id``). Resolve the armor-trim
        smithing recipes — ``<template>_smithing_trim`` — to a smithing table + that trim template
        (structure loot); other no-ingredient recipes fall back to the parent chain."""
        if not isinstance(recipe_id, str):
            return None
        base = recipe_id.split(":", 1)[-1]
        suffix = "_smithing_trim"
        if base.endswith(suffix):
            template = base[: -len(suffix)]
            return self._all_req(self.h.acquire("minecraft:smithing_table"),
                                 self.h.acquire(f"minecraft:{template}"))
        return None

    def _location_node(self, cond: dict) -> Rule | None:
        loc = self._predicate_value(cond.get("player"), "location")
        if not isinstance(loc, dict):
            return None
        struct = loc.get("structures")
        if isinstance(struct, str):
            name = self._struct_by_gid.get(struct)
            return self.h.structure(name) if name else None
        if "biomes" in loc:
            # Locating a specific biome needs the Biome Finder (when enabled); the advancement's own
            # region placement already gates which dimension it's in.
            return self.h.needs_biome_finder()
        dim = loc.get("dimension")
        region = _DIMENSION_REGION.get(dim) if isinstance(dim, str) else None
        return self.h.access_region(region) if region else None

    def _inventory_node(self, cond: dict) -> Rule | None:
        return self._all_items(cond.get("items"))

    def _all_items(self, predicates) -> Rule | None:
        """AND over a list of item predicates (an ``inventory_changed`` items list or a
        ``recipe_crafted`` ingredients list): every one must be obtainable, else fall back."""
        if not isinstance(predicates, list) or not predicates:
            return None
        parts: list[Rule] = []
        for pred in predicates:
            node = self._item_predicate(pred)
            if node is None:
                return None  # a required item we can't resolve → fall back entirely
            parts.append(node)
        return and_(*parts)

    def _species_from_components(self, cond: dict) -> str | None:
        """Infer the species of a variant-only entity predicate (e.g. a cat colour, which pins no
        ``type``) from its component key ``minecraft:<species>/variant``."""
        components = self._predicate_value(cond.get("entity"), "components")
        if not isinstance(components, dict):
            return None
        for key in components:
            match = re.match(r"minecraft:([a-z_]+)/variant", key)
            if match:
                return self._entity_by_gid.get(f"minecraft:{match.group(1)}")
        return None

    def _item_predicate(self, pred) -> Rule | None:
        """Resolve one item predicate (``{"items": <id|[ids]|#tag>}``) to acquisition logic — or,
        for a potion item carrying a ``potion_contents`` component, its brewing chain. When the
        predicate also demands the item be ENCHANTED, the enchanting-capability gate is AND-ed in
        (else "an enchanted sword" would compile as merely "a sword")."""
        if not isinstance(pred, dict):
            return None
        potion = self._potion_node(pred)
        if potion is not None:
            return potion
        enchant = self._enchant_gate(pred)
        base = self._any_acquire(pred.get("items"))
        if enchant is not None:
            # The item must be enchanted: need the base item (when named) AND a way to enchant it.
            return and_(base, enchant) if base is not None else enchant
        return base

    def _enchant_gate(self, pred: dict) -> Rule | None:
        """The capability to get an ENCHANTED item, or ``None`` when the predicate names no
        enchantment. Two routes: the enchanting table (``acquire`` gates it behind Knowledge:
        Enchanting + its tier), OR an enchanted book — a librarian's trade gives one with no
        Knowledge needed, applied to the item with an anvil (when the item itself, not the book,
        must carry the enchantment, i.e. ``enchantments`` predicate vs ``stored_enchantments``)."""
        predicates = pred.get("predicates")
        if not isinstance(predicates, dict):
            return None
        on_item = "enchantments" in predicates
        on_book = "stored_enchantments" in predicates
        if not on_item and not on_book:
            return None
        routes = [self.h.acquire("minecraft:enchanting_table")]
        book = self.h.acquire("minecraft:enchanted_book")  # librarian trades; no Knowledge needed
        if book is not None:
            # A stored_enchantments target *is* the book; an enchantments target needs it applied
            # with an anvil. (A librarian's book has a random enchant — a trade path, as elsewhere.)
            routes.append(and_(book, self.h.acquire("minecraft:anvil")) if on_item else book)
        return or_(*routes)

    def _potion_node(self, pred: dict) -> Rule | None:
        """A specific brewed potion: a brewing stand + Knowledge: Brewing + a glass bottle + every
        reagent of its type (see brewing.json). Returns ``None`` for a non-potion or an untyped
        potion (which falls through to its loot/trade sources)."""
        items = pred.get("items")
        ids = items if isinstance(items, list) else [items]
        if not any(item in _POTION_ITEMS for item in ids):
            return None
        contents = self._component(pred, "minecraft:potion_contents")
        potion_type = contents.get("potion") if isinstance(contents, dict) else None
        if not isinstance(potion_type, str):
            return None
        potion_type = potion_type.split(":")[-1]
        for prefix in ("long_", "strong_"):
            potion_type = potion_type.removeprefix(prefix)
        reagents = _brewing().get(potion_type)
        if reagents is None:
            return None
        parts = [self.h.acquire("minecraft:brewing_stand"), self.h.knowledge(K_BREWING),
                 self.h.acquire("minecraft:glass_bottle")]
        parts += [self.h.acquire(f"minecraft:{reagent}") for reagent in reagents]
        parts = [node for node in parts if node is not None]
        return and_(*parts) if parts else None

    @staticmethod
    def _component(pred: dict, key: str):
        """A component value from an item predicate's ``components`` / ``predicates`` block."""
        for holder in ("components", "predicates"):
            block = pred.get(holder)
            if isinstance(block, dict) and key in block:
                return block[key]
        return None

    def _any_acquire(self, ids) -> Rule | None:
        """OR over ``acquire`` of one item id, a list of them, or an item ``#tag`` (the items are
        alternatives; a tag expands to its members)."""
        if isinstance(ids, str):
            ids = [ids]
        if not isinstance(ids, list) or not ids:
            return None
        options = []
        for item_id in ids:
            for resolved in self._expand_item(item_id):
                node = self.h.acquire(resolved)
                if node is not None:
                    options.append(node)
        return or_(*options) if options else None

    def _expand_item(self, item_id: str) -> list:
        """An item id as-is, or a ``#tag`` expanded to its member item ids."""
        if isinstance(item_id, str) and item_id.startswith("#"):
            return self._item_tags.get(item_id[1:], [])
        return [item_id] if isinstance(item_id, str) else []

    def _used_item_node(self, cond: dict) -> Rule | None:
        """``using_item`` / ``shot_crossbow``: obtain the item, and — when the criterion pins what
        the player is aiming at — also reach that entity."""
        item = self._item_predicate(cond.get("item"))
        if item is None:
            return None
        specific = self._predicate_value(cond.get("player"), "type_specific")
        looking = specific.get("looking_at") if isinstance(specific, dict) else None
        gid = looking.get("type") if isinstance(looking, dict) else None
        target = self._entity_by_gid.get(gid) if gid else None
        return and_(item, self.h.entity(target)) if target in MOBS_ALL else item

    def _placed_block_node(self, cond: dict) -> Rule | None:
        """``placed_block``: obtain the block being placed."""
        return self._any_acquire(self._blocks_in(cond))

    def _blocks_in(self, cond: dict) -> list:
        """Every block id a block-interaction criterion references. Tolerates the flat
        ``location:[{block: id}]`` form (vanilla ``block_state_property``) and BACAP's nested
        ``location_check`` → ``predicate.block.blocks`` form, plus a top-level ``block`` predicate
        (``enter_block``). ``#tag`` ids are kept — ``_any_acquire`` expands them."""
        blocks: list = []

        def add(value):
            if isinstance(value, str):
                blocks.append(value)
            elif isinstance(value, dict):
                ids = value.get("blocks")
                if isinstance(ids, str):  # vanilla writes a single block as a bare string
                    blocks.append(ids)
                elif isinstance(ids, list):
                    blocks.extend(b for b in ids if isinstance(b, str))

        location = cond.get("location")
        entries = location if isinstance(location, list) else [location]
        for entry in entries:
            if isinstance(entry, dict):
                add(entry.get("block"))
                pred = entry.get("predicate")
                if isinstance(pred, dict):
                    add(pred.get("block"))
        add(cond.get("block"))
        return blocks

    @staticmethod
    def _all_req(*nodes) -> Rule | None:
        """AND of nodes that are ALL required — ``None`` (fall back) if any is unresolved, so a
        half-built gate never silently weakens to its resolvable half."""
        return None if any(n is None for n in nodes) else and_(*nodes)

    @staticmethod
    def _any_opt(*nodes) -> Rule | None:
        """OR over the resolvable nodes (alternative sources); ``None`` if none resolved."""
        present = [n for n in nodes if n is not None]
        return or_(*present) if present else None

    def _entity_gid(self, gid: str) -> Rule | None:
        """Reach the entity with this game id, or ``None`` when the active packs lack it."""
        name = self._entity_by_gid.get(gid)
        return self.h.entity(name) if name in MOBS_ALL else None

    def _struct_gid(self, gid: str) -> Rule | None:
        """Reach the structure with this game id, or ``None`` when the active packs lack it."""
        name = self._struct_by_gid.get(gid)
        return self.h.structure(name) if name in STRUCTURES else None

    def _cure_zombie_node(self) -> Rule:
        """Cure a Zombie Villager: reach one, plus a golden apple and a Potion of Weakness
        (brewing stand + Knowledge: Brewing + a fermented spider eye)."""
        parts = [self._entity_gid("minecraft:zombie_villager"),
                 self.h.acquire("minecraft:golden_apple"),
                 self.h.acquire("minecraft:brewing_stand"), self.h.knowledge(K_BREWING),
                 self.h.acquire("minecraft:fermented_spider_eye")]
        return self.h.all_of(*[p for p in parts if p is not None])

    def _sculk_kill_node(self, cond: dict) -> Rule:
        """Kill a mob near a sculk catalyst → be in the Deep Dark (Ancient City) and, when the
        criterion pins a victim, reach it too."""
        deep_dark = self._struct_gid("minecraft:ancient_city")
        victim = self._entity_node(cond)
        parts = [p for p in (deep_dark, victim) if p is not None]
        return self.h.all_of(*parts) if parts else self.h.access_region(REGION_OVERWORLD)

    def _started_riding_node(self, cond: dict) -> Rule | None:
        """Ride a vehicle: a placeable item (minecart / boat) is acquired; a mount is reached."""
        gid = self._predicate_value(cond.get("player"), "vehicle")
        gid = gid.get("type") if isinstance(gid, dict) else gid
        if not isinstance(gid, str):
            return None
        mount = self._entity_gid(gid)
        if mount is not None:
            return mount
        return self.h.acquire(gid)  # minecart / *_boat / *_raft are items

    def _effects_node(self, cond: dict) -> Rule:
        """Gain a status effect. Most effects come from a brewed potion, so gate on brewing
        capability; a few environmental ones map to their source."""
        effects = cond.get("effects")
        names = list(effects) if isinstance(effects, dict) else []
        parts = []
        for effect in names:
            source = _EFFECT_SOURCE.get(effect)
            if source is not None:
                node = source(self)
                if node is not None:
                    parts.append(node)
        parts.append(self.h.all_of(self.h.acquire("minecraft:brewing_stand"),
                                   self.h.knowledge(K_BREWING)))
        return self.h.all_of(*parts)

    def _used_on_block_node(self, cond: dict) -> Rule | None:
        """``item_used_on_block``: require BOTH the item used (top-level ``item`` or a ``match_tool``
        predicate) AND the target block. Dropping the block let Not Quite Nine Lives pass on the
        glowstone alone without the respawn anchor (crying obsidian → Nether), and Country Lode on
        the compass without the lodestone (netherite → Nether). An unresolvable half is omitted."""
        item = self._item_predicate(cond.get("item")) if "item" in cond else None
        if item is None:
            location = cond.get("location")
            for sub in (location if isinstance(location, list) else [location]):
                if isinstance(sub, dict) and sub.get("condition") == "minecraft:match_tool":
                    item = self._any_acquire((sub.get("predicate") or {}).get("items"))
                    break
        block = self._any_acquire(self._blocks_in(cond))
        parts = [n for n in (item, block) if n is not None]
        return and_(*parts) if parts else None

    def _any_mob(self, mobs, build) -> Rule | None:
        """OR over a per-mob rule builder (``can_breed`` / ``can_tame``) for a whole mob set."""
        options = [build(name) for name in mobs]
        return or_(*options) if options else None

    def _filled_bucket_node(self, cond: dict) -> Rule | None:
        """``filled_bucket``: hold a bucket and, for a captured mob, reach it."""
        ids = (cond.get("item") or {}).get("items")
        item = ids[0] if isinstance(ids, list) and ids else ids
        if not isinstance(item, str):
            return None
        bucket = self.h.acquire("minecraft:bucket")
        if bucket is None:
            return None
        content = item.split(":")[-1].replace("_bucket", "")
        mob = self._entity_by_gid.get(f"minecraft:{content}")
        return and_(bucket, self.h.entity(mob)) if mob in MOBS_ALL else bucket

    def _container_loot_node(self, cond: dict) -> Rule | None:
        """``player_generates_container_loot``: reach the structure whose loot table this is. The
        table name may be a variant (``bastion_bridge``), so fall back to matching a structure that
        shares its leading segment (``bastion`` → Bastion Remnant)."""
        table = cond.get("loot_table")
        if not isinstance(table, str):
            return None
        tail = table.split("/")[-1]
        name = self._struct_by_gid.get(f"minecraft:{tail}")
        if name is None:
            head = tail.split("_")[0]
            name = next((n for gid, n in self._struct_by_gid.items()
                         if gid.split(":")[-1].split("_")[0] == head), None)
        return self.h.structure(name) if name else None

    @staticmethod
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
