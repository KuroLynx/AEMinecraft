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
    LOCATIONS_ADVANCEMENT,
    MOBS_ALL,
    MOBS_BREEDABLE,
    MOBS_TAMEABLE,
    STRUCTURES,
)
from .acquisition import RuleHelper
from .ast import Rule, and_, or_
from .constants import REGION_END, REGION_NETHER, REGION_OVERWORLD

# Triggers that imply a specific tool/block the criterion never names: hitting a target block is
# gated by crafting one (redstone + hay), brewing by a brewing stand, etc. Reaching the implied item
# is the meaningful gate, so the advancement inherits its acquisition logic.
_IMPLIED_ITEM = {
    "minecraft:target_hit": "minecraft:target",
    "minecraft:brewed_potion": "minecraft:brewing_stand",
    "minecraft:enchanted_item": "minecraft:enchanting_table",
    "minecraft:fishing_rod_hooked": "minecraft:fishing_rod",
}

_TAGS: dict | None = None


def _tags() -> dict:
    """Lazily-loaded item + entity-type tag table (tools/build_tags.py), keyed by tag id."""
    global _TAGS
    if _TAGS is None:
        root = __package__.rsplit(".", 1)[0]  # e.g. "worlds.minecraft"
        with files(root).joinpath("packs", "vanilla_26_1", "tags.json").open(encoding="utf-8") as f:
            _TAGS = json.load(f)
    return _TAGS

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
    "minecraft:player_hurt_entity",
    "minecraft:entity_hurt_player",
    "minecraft:killed_by_arrow",
    "minecraft:player_interacted_with_entity",
    "minecraft:summoned_entity",
})


class TriggerCompiler:
    """Compiles a manifest record into a :class:`Rule`, or ``None`` if not confidently derivable."""

    def __init__(self, helper: RuleHelper):
        self.h = helper
        # Reverse lookups from Minecraft id -> our display-name key.
        self._entity_by_gid = {data.game_id: name for name, data in MOBS_ALL.items()}
        self._struct_by_gid = {data.game_id: name for name, data in STRUCTURES.items()}
        self._adv_loc_by_gid = {data.game_id: name for name, data in LOCATIONS_ADVANCEMENT.items()}
        self._item_tags = _tags().get("item", {})
        self._entity_tags = _tags().get("entity_type", {})

    # -- public -------------------------------------------------------------
    def compile(self, record: dict) -> Rule | None:
        """AST for ``record``'s requirements, or ``None`` if any AND-group is uninterpretable."""
        criteria = record.get("criteria", {})
        requirements = record.get("requirements") or []
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
        return self.h.reached(loc) if loc else None

    # -- per-criterion dispatch --------------------------------------------
    def _criterion(self, crit: dict) -> Rule | None:
        trigger = crit.get("trigger")
        cond = crit.get("conditions") or {}

        if trigger in _ENTITY_REACH_TRIGGERS:
            return self._entity_node(cond)
        if trigger == "minecraft:tame_animal":
            name = self._entity_name(cond) or self._species_from_components(cond)
            if name:
                return self.h.can_tame(name)
            return self._any_mob(MOBS_TAMEABLE, self.h.can_tame) if not cond else None
        if trigger == "minecraft:bred_animals":
            # The bred species is pinned on the `child` predicate (e.g. bred_all_animals); empty
            # conditions mean "breed any animal".
            gid = self._predicate_value(cond.get("child"), "type")
            name = self._entity_by_gid.get(gid) if gid else None
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
        if trigger == "minecraft:item_used_on_block":
            return self._used_on_block_node(cond)
        if trigger == "minecraft:filled_bucket":
            return self._filled_bucket_node(cond)
        if trigger == "minecraft:recipe_crafted":
            return self._all_items(cond.get("ingredients"))
        if trigger == "minecraft:construct_beacon":
            return self.h.acquire("minecraft:beacon")
        if trigger == "minecraft:villager_trade":
            return self.h.can_trade_villager()
        if trigger == "minecraft:slept_in_bed":
            return self.h.access_region(REGION_OVERWORLD)  # a bed needs wool + planks (overworld)
        if trigger == "minecraft:used_totem":
            return self.h.acquire("minecraft:totem_of_undying")
        if trigger == "minecraft:player_generates_container_loot":
            return self._container_loot_node(cond)
        if trigger in _IMPLIED_ITEM:
            return self.h.acquire(_IMPLIED_ITEM[trigger])
        return None

    # -- condition extractors ----------------------------------------------
    def _entity_node(self, cond: dict) -> Rule | None:
        """Reach the entity a criterion's `entity` predicate pins via `type` — a single id, or a
        ``#tag`` (e.g. ``#raiders``) expanded to an OR over its members."""
        gid = self._predicate_value(cond.get("entity"), "type")
        if not isinstance(gid, str):
            return None
        members = self._entity_tags.get(gid[1:], []) if gid.startswith("#") else [gid]
        names = [self._entity_by_gid[m] for m in members if m in self._entity_by_gid]
        options = [self.h.entity(name) for name in names]
        return or_(*options) if options else None

    def _entity_name(self, cond: dict) -> str | None:
        """Display-name for the entity a criterion's `entity` predicate pins via a concrete type."""
        gid = self._predicate_value(cond.get("entity"), "type")
        return self._entity_by_gid.get(gid) if isinstance(gid, str) else None

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
        """Resolve one item predicate (``{"items": <id|[ids]|#tag>}``) to acquisition logic."""
        if not isinstance(pred, dict):
            return None
        return self._any_acquire(pred.get("items"))

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
        entries = cond.get("location") if isinstance(cond.get("location"), list) else []
        blocks = [e["block"] for e in entries
                  if isinstance(e, dict) and isinstance(e.get("block"), str)]
        return self._any_acquire(blocks)

    def _used_on_block_node(self, cond: dict) -> Rule | None:
        """``item_used_on_block``: the item used (top-level ``item`` or a ``match_tool`` predicate),
        else the target block(s)."""
        if "item" in cond:
            return self._item_predicate(cond.get("item"))
        location = cond.get("location")
        if isinstance(location, list):
            for sub in location:
                if isinstance(sub, dict) and sub.get("condition") == "minecraft:match_tool":
                    tool = self._any_acquire(sub.get("predicate", {}).get("items"))
                    if tool is not None:
                        return tool
        block = self._predicate_value(location, "block")
        return self._any_acquire(block.get("blocks")) if isinstance(block, dict) else None

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
