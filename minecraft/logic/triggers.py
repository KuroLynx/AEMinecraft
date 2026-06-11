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

from ..data import LOCATIONS_ADVANCEMENT, MOBS_ALL, STRUCTURES
from .acquisition import RuleHelper
from .ast import Rule, and_, or_
from .constants import REGION_END, REGION_NETHER, REGION_OVERWORLD

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
    """Compiles one manifest record into a :class:`Rule`, or ``None`` if not confidently derivable."""

    def __init__(self, helper: RuleHelper):
        self.h = helper
        # Reverse lookups from Minecraft id -> our display-name key.
        self._entity_by_gid = {data.game_id: name for name, data in MOBS_ALL.items()}
        self._struct_by_gid = {data.game_id: name for name, data in STRUCTURES.items()}
        self._adv_loc_by_gid = {data.game_id: name for name, data in LOCATIONS_ADVANCEMENT.items()}

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
            name = self._entity_name(cond)
            return self.h.can_tame(name) if name else None
        if trigger == "minecraft:bred_animals":
            # The bred species is pinned on the `child` predicate (e.g. bred_all_animals); empty
            # conditions ("breed any animal") aren't tied to a species, so they fall back.
            gid = self._predicate_value(cond.get("child"), "type")
            name = self._entity_by_gid.get(gid) if gid else None
            return self.h.can_breed(name) if name else None
        if trigger == "minecraft:changed_dimension":
            region = _DIMENSION_REGION.get(cond.get("to"))
            return self.h.access_region(region) if region else None
        if trigger == "minecraft:location":
            return self._location_node(cond)
        if trigger == "minecraft:inventory_changed":
            return self._inventory_node(cond)
        if trigger == "minecraft:consume_item":
            # "Eat any item" (empty conditions) → any food source; item-specific consume falls back.
            return self.h.can_get_food() if not cond else None
        return None

    # -- condition extractors ----------------------------------------------
    def _entity_node(self, cond: dict) -> Rule | None:
        name = self._entity_name(cond)
        return self.h.entity(name) if name else None

    def _entity_name(self, cond: dict) -> str | None:
        """Display-name for the entity a criterion's `entity` predicate pins via `type`."""
        gid = self._predicate_value(cond.get("entity"), "type")
        return self._entity_by_gid.get(gid) if gid else None

    def _location_node(self, cond: dict) -> Rule | None:
        loc_pred = self._predicate_value(cond.get("player"), "location") or {}
        struct = loc_pred.get("structures") if isinstance(loc_pred, dict) else None
        if isinstance(struct, str):
            name = self._struct_by_gid.get(struct)
            return self.h.structure(name) if name else None
        # Biome / dimension location predicates are not modelled here (biome reachability is its own
        # concern); leave them to a curated override / parent fallback.
        return None

    def _inventory_node(self, cond: dict) -> Rule | None:
        # Acquisition of arbitrary items is not generally modelled yet; only a kill/structure/dim
        # criterion compiles cleanly. Item gating falls back to the parent chain for now.
        return None

    @staticmethod
    def _predicate_value(entity_conditions, key: str):
        """Pull ``predicate[key]`` out of a criterion sub-condition, tolerating both the list form
        ``[{"condition": "entity_properties", "predicate": {...}}]`` and a bare ``{...}`` predicate."""
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
