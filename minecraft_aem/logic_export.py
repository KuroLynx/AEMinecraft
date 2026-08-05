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
      "glitch": { "<AP location name>": {"rule": <ast>} },
      "definitions": { "<id>": <ast>, ... }
    }

``glitch`` holds the permissive rule for the locations whose two graphs disagree (see
:func:`_glitch_rules`); the mod falls back to it when the strict rule says unreachable, to colour a
tile GLITCHABLE instead of out-of-logic. Absent locations have identical rules in both graphs, and
the whole map is empty when the glitch_logic option is off.

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

import copy
from collections import Counter

from .data import MCLocationCategory
from .logic.ast import ReachLocation, at_least
from .logic.constants import REWARD_EVENT_PREFIX
from .regions import MCRegion

# game_id of the Archipelago advancement-tab root tile (mirrors APTrackerRegistry.TAB_ROOT_ID).
AP_TAB_ROOT_GAME_ID = "aem:archipelago"


def _region_name(region) -> str:
    """Plain region name for the export. An MCRegion is a (str, Enum) member whose str() is
    "MCRegion.OVERWORLD"; `region + ""` returns the raw underlying value ("Overworld") instead, so
    the shipped location regions match the origin / region-graph edges (which use MCRegion.value).
    Mirrors ast.ReachRegion's normalisation."""
    return region + "" if isinstance(region, str) else str(region)


def build_logic_export(world) -> dict:
    # Locations created this seed, keyed name -> MCLocationData. Uses the world's own active set
    # (not the vanilla-only ALL_LOCATIONS) so optional packs like BACAP — whose advancements also
    # appear in logic_rules — resolve here instead of raising KeyError.
    loc_lookup = world._get_active_locations()

    # Actual region each location was placed in (create_regions derives it from the rule, so it is not
    # the data-declared default) — the mod gates region reachability on this. Normalise to the plain
    # region value: parent_region.name is an MCRegion (str, Enum) member, and AP's slot_data encoder
    # (NetUtils.convert_to_base_types) stringifies it via Enum.__str__ to "MCRegion.OVERWORLD" — which
    # would never match the ".value" names ("Overworld") the origin and region-graph edges ship with,
    # so the mod could not reach any location's region and every tile rendered out-of-logic (red).
    # (`region + ""` yields the raw str buffer, like ast.ReachRegion; plain json.dumps hides the bug.)
    placed_region = {loc.name: _region_name(loc.parent_region.name)
                     for loc in world.multiworld.get_locations(world.player)}

    # Per-location rules captured during set_rules (only locations created this seed). BACAP reward
    # events (REWARD_EVENT_PREFIX) are internal generation-only locations holding a non-networked
    # event item that the Java client never receives — so a has(<event>) leaf would read 0 in-game.
    # They're held out here and inlined below: each event's rule (OR of reaching a granting
    # advancement) replaces every has(<event>) leaf, so the mod's monotone sweep evaluates rewards
    # through plain loc nodes (it resolves cycles by fixed point, unlike AP's recursive reached()).
    locations: dict[str, dict] = {}
    reward_event_rules: dict[str, dict] = {}
    for name, rule in getattr(world, "logic_rules", {}).items():
        if name.startswith(REWARD_EVENT_PREFIX):
            reward_event_rules[name] = rule.to_dict()
            continue
        loc_data = loc_lookup[name]
        locations[name] = {
            "game_id": loc_data.game_id,
            "region": placed_region.get(name, _region_name(loc_data.region)),
            "rule": rule.to_dict(),
        }

    # Region graph captured during create_regions.
    regions: dict[str, list] = {}
    for from_region, entrances in getattr(world, "logic_region_rules", {}).items():
        regions[from_region] = [
            {"to": entrance["to"], "rule": entrance["rule"].to_dict()}
            for entrance in entrances
        ]

    # Inline reward-event leaves in every exported rule (location + region edge) before the tab root
    # and dedup pass, so the CSE in _dedup_rules can hoist the shared event subtrees.
    if reward_event_rules:
        for entry in locations.values():
            entry["rule"] = _inline_reward_events(entry["rule"], reward_event_rules)
        for edges in regions.values():
            for edge in edges:
                edge["rule"] = _inline_reward_events(edge["rule"], reward_event_rules)

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
        "glitch": _glitch_rules(world, locations, reward_event_rules),
    }
    _dedup_rules(export)
    return export


def _glitch_rules(world, locations: dict, reward_event_rules: dict) -> dict:
    """The permissive twin of each location rule, ``{name: {"rule": <ast>}}`` — but ONLY where it
    differs from the strict one.

    Strict logic drops routes the player can't count on (RuleHelper._demote), which is right for item
    placement and wrong for a tracker: a check you could do this minute with a bit of luck would sit
    there red. The mod evaluates this second rule for anything strict logic calls unreachable, and
    paints it GLITCHABLE (yellow) when it passes.

    Most locations compile identically in both graphs, and shipping only the differences keeps this
    close to free — the shared subtrees are hoisted into the same ``definitions`` table by
    _dedup_rules, so a glitch rule is usually a handful of refs.

    Empty when the player turned glitch_logic off, which is the whole of that option: it changes what
    the tracker tells you and nothing else. Placement never consults this graph.

    Only LOCATION rules get a twin, and today that costs nothing: every region edge built in
    create_regions is has/reached/knowledge — leaves that read the same in both graphs — except
    can_get_obsidian(), which serializes identically strict and glitch under either start dimension.
    An edge built from a demotable helper would be the case to revisit.
    """
    if not world.options.glitch_logic:
        return {}
    from .logic.root import build_location_rules  # local import: module-level would cycle

    glitch: dict[str, dict] = {}
    for name, rule in build_location_rules(world, glitch=True).items():
        if name not in locations:
            continue  # a reward event or a location this seed didn't create
        as_dict = _inline_reward_events(rule.to_dict(), reward_event_rules) \
            if reward_event_rules else rule.to_dict()
        if as_dict != locations[name]["rule"]:
            glitch[name] = {"rule": as_dict}
    return glitch


def _inline_reward_events(node: dict, reward_event_rules: dict[str, dict]) -> dict:
    """Replace every ``has(<reward event>)`` leaf with that event's rule (the OR of reaching a granting
    advancement), recursively. Returns a new tree; a leaf with no matching event (shouldn't occur)
    collapses to ``const False`` so a stray reference never reads as obtainable in-game."""
    if node.get("k") == "has" and node.get("i", "").startswith(REWARD_EVENT_PREFIX):
        replacement = reward_event_rules.get(node["i"])
        return copy.deepcopy(replacement) if replacement is not None else {"k": "const", "v": False}
    children = node.get("c")
    if children is not None:
        return {**node, "c": [_inline_reward_events(child, reward_event_rules) for child in children]}
    return node


def _dedup_rules(export: dict) -> None:
    """Common-subexpression elimination over the exported rule forest, in place.

    Every composite subtree that occurs more than once (within one rule or across rules) is hoisted
    into ``export["definitions"]`` once and replaced by a ``{"k": "ref", "id": N}`` node. The bloat
    in a BACAP export is the SAME acquisition subtrees (planks, dyes, an entity-unlock-bearing tree)
    re-inlined thousands of times; sharing them collapses a multi-megabyte export to a small one
    with identical logic. Leaves (``has``/``region``/``loc``/``const``) are left inline — they are
    smaller than a ref, so hoisting them would only bloat the table."""
    holders = list(export["locations"].values())
    holders.extend(export.get("glitch", {}).values())
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
