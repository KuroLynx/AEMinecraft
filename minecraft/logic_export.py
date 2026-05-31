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

from .data import ALL_LOCATIONS
from .regions import MCRegion


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

    return {
        "origin": MCRegion.MENU.value,
        "regions": regions,
        "locations": locations,
    }
