"""Serializes the world's logic graph for the Fabric mod.

The mod can't run the apworld, so we ship the region graph + per-location reachability
rules (as serialized AST nodes, see ``rules/ast.py``) in ``slot_data``. The mod's Java
evaluator interprets this against the items the slot has received to decide, per
advancement, whether it is reachable now (in logic), not yet (out of logic), or checked.

Shape::

    {
      "origin": "Menu",
      "regions": { "<region>": [ {"to": "<region>", "rule": <ast>}, ... ] },
      "locations": {
         "<AP location name>": {"game_id": "<mc id>", "region": "<region>", "rule": <ast>}
      },
      "definitions": { "<id>": <ast>, ... }
    }

All keys/strings are plain (region names are normalised away from enum members in
``create_regions``). Rules contain only the primitive node kinds documented in
``logic/ast.py`` — plus ``{"k": "ref", "id": <int>}``, a pointer into ``definitions``. Refs are a
pure serialization shrink (see :func:`_dedup_rules`): a shared subtree is emitted once in
``definitions`` and referenced everywhere it occurs, so a BACAP slot_data stays small instead of
re-inlining the same acquisition subtrees thousands of times. The mod resolves a ref by evaluating
its definition (``RuleNode.Ref`` / ``LogicGraph``); the apworld's own reachability never sees refs
(it evaluates the live AST), so generation is unaffected.
"""
from __future__ import annotations

from collections import Counter

from .data import MCLocationCategory
from .logic.ast import ReachLocation, at_least
from .regions import MCRegion

# game_id of the Archipelago advancement-tab root tile (mirrors APTrackerRegistry.TAB_ROOT_ID).
AP_TAB_ROOT_GAME_ID = "aem:archipelago"


def build_logic_export(world) -> dict:
    # Locations created this seed, keyed name -> MCLocationData. Uses the world's own active set
    # (not the vanilla-only ALL_LOCATIONS) so optional packs like BACAP — whose advancements also
    # appear in logic_rules — resolve here instead of raising KeyError.
    loc_lookup = world._get_active_locations()

    # Actual region each location was placed in (create_regions derives it from the rule, so it is not
    # the data-declared default) — the mod gates region reachability on this.
    placed_region = {loc.name: loc.parent_region.name
                     for loc in world.multiworld.get_locations(world.player)}

    # Per-location rules captured during set_rules (only locations created this seed).
    locations: dict[str, dict] = {}
    for name, rule in getattr(world, "logic_rules", {}).items():
        loc_data = loc_lookup[name]
        locations[name] = {
            "game_id": loc_data.game_id,
            "region": placed_region.get(name, loc_data.region),
            "rule": rule.to_dict(),
        }

    # Region graph captured during create_regions.
    regions: dict[str, list] = {}
    for from_region, entrances in getattr(world, "logic_region_rules", {}).items():
        regions[from_region] = [
            {"to": entrance["to"], "rule": entrance["rule"].to_dict()}
            for entrance in entrances
        ]

    origin = MCRegion.MENU.value

    # Synthetic entry for the Archipelago tab root tile: it's a datapack advancement, not an AP
    # location, so without this it would never be coloured. Its rule mirrors the goal's advancement
    # gate — "at least `advancements_required` advancement locations reachable" — so the root tile
    # turns in-logic exactly when enough advancements become completable. Region is the always-reachable
    # origin, leaving the count rule as the only gate. (Bosses are tracked by their own kill tiles.)
    advancement_names = [
        name for name in locations
        if loc_lookup[name].category == MCLocationCategory.ADVANCEMENT
    ]
    required = min(world.options.advancements_required.value, len(advancement_names))
    root_rule = at_least(required, [ReachLocation(world.player, name) for name in advancement_names])
    locations[f"__{AP_TAB_ROOT_GAME_ID}__"] = {
        "game_id": AP_TAB_ROOT_GAME_ID,
        "region": origin,
        "rule": root_rule.to_dict(),
    }

    export = {
        "origin": origin,
        "regions": regions,
        "locations": locations,
    }
    _dedup_rules(export)
    return export


def _dedup_rules(export: dict) -> None:
    """Common-subexpression elimination over the exported rule forest, in place.

    Every composite subtree that occurs more than once (within one rule or across rules) is hoisted
    into ``export["definitions"]`` once and replaced by a ``{"k": "ref", "id": N}`` node. The bloat
    in a BACAP export is the SAME acquisition subtrees (planks, dyes, an entity-unlock-bearing tree)
    re-inlined thousands of times; sharing them collapses a multi-megabyte export to a small one
    with identical logic. Leaves (``has``/``region``/``loc``/``const``) are left inline — they are
    smaller than a ref, so hoisting them would only bloat the table."""
    holders = list(export["locations"].values())
    for edges in export["regions"].values():
        holders.extend(edges)

    # Canonical key of a subtree (commutative children sorted so reordered ANDs/ORs still share).
    # ("n", …) marks a composite (hoistable), ("l", …) a leaf.
    occurrences: Counter = Counter()

    def key_of(node: dict):
        children = node.get("c")
        if children is None:
            key = ("l", node["k"], node.get("i"), node.get("n"), node.get("r"),
                   node.get("l"), node.get("v"))
        else:
            child_keys = tuple(key_of(child) for child in children)
            if node["k"] in ("and", "or", "atleast"):
                child_keys = tuple(sorted(child_keys))
            key = ("n", node["k"], node.get("n"), child_keys)
        occurrences[key] += 1
        return key

    for holder in holders:
        key_of(holder["rule"])

    shareable = {key for key, count in occurrences.items() if count >= 2 and key[0] == "n"}
    if not shareable:
        export["definitions"] = {}
        return

    ref_ids: dict = {}
    definitions: dict = {}

    def rewrite(node: dict):
        """Return ``(rewritten_node, original_canonical_key)`` — the key stays the pre-rewrite one
        so a parent's key matches the counting pass even when a child became a ref."""
        children = node.get("c")
        if children is None:
            key = ("l", node["k"], node.get("i"), node.get("n"), node.get("r"),
                   node.get("l"), node.get("v"))
            new_node = node
        else:
            rewritten = [rewrite(child) for child in children]
            child_keys = tuple(child_key for _, child_key in rewritten)
            if node["k"] in ("and", "or", "atleast"):
                child_keys = tuple(sorted(child_keys))
            key = ("n", node["k"], node.get("n"), child_keys)
            new_node = {**node, "c": [child for child, _ in rewritten]}
        if key in shareable:
            ref_id = ref_ids.get(key)
            if ref_id is None:
                ref_id = ref_ids[key] = len(ref_ids)
                definitions[ref_id] = new_node  # children already refs → nested sharing
            return {"k": "ref", "id": ref_id}, key
        return new_node, key

    for holder in holders:
        holder["rule"] = rewrite(holder["rule"])[0]

    export["definitions"] = {str(ref_id): node for ref_id, node in definitions.items()}
