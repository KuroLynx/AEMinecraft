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
      }
    }

All keys/strings are plain (region names are normalised away from enum members in
``create_regions``). Rules contain only the primitive node kinds documented in
``rules/ast.py``.
"""
from __future__ import annotations

from .data import ALL_LOCATIONS, MCLocationCategory
from .regions import MCRegion
from .logic.ast import ReachLocation, at_least

# game_id of the Archipelago advancement-tab root tile (mirrors APTrackerRegistry.TAB_ROOT_ID).
AP_TAB_ROOT_GAME_ID = "aem:archipelago"


def build_logic_export(world) -> dict:
    # Per-location rules captured during set_rules (only locations created this seed).
    locations: dict[str, dict] = {}
    for name, rule in getattr(world, "logic_rules", {}).items():
        loc_data = ALL_LOCATIONS[name]
        locations[name] = {
            "game_id": loc_data.game_id,
            "region": loc_data.region,
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
        if ALL_LOCATIONS[name].category == MCLocationCategory.ADVANCEMENT
    ]
    required = min(world.options.advancements_required.value, len(advancement_names))
    root_rule = at_least(required, [ReachLocation(world.player, name) for name in advancement_names])
    locations[f"__{AP_TAB_ROOT_GAME_ID}__"] = {
        "game_id": AP_TAB_ROOT_GAME_ID,
        "region": origin,
        "rule": root_rule.to_dict(),
    }

    return {
        "origin": origin,
        "regions": regions,
        "locations": locations,
    }
