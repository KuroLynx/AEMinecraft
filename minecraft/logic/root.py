import json
from importlib.resources import files

from worlds.generic.Rules import set_rule

from ..data import ADVANCEMENT_LOCATIONS, MOBS_ALL, MCEntityCategory
from ..content.registry import base_pack, overlay_packs
from .acquisition import RuleHelper
from .ast import Const
from .constants import *
from .engine import collect_advancement_rules, collect_entity_rules
from .triggers import TriggerCompiler


def _manifest(pack_name: str) -> dict:
    """A pack's advancement manifest (tools/extract_manifest.py), keyed by advancement game_id."""
    root = __package__.rsplit(".", 1)[0]  # e.g. "worlds.minecraft"
    with files(root).joinpath("packs", pack_name, "manifest.json").open(encoding="utf-8") as f:
        return json.load(f)


def build_location_rules(world) -> dict:
    """The final reachability rule for every active location this seed (advancements + mob/boss kills),
    keyed by location name. Advancement logic is derived from the criteria (TriggerCompiler), with the
    curated rule, then the parent chain, then ``Const(True)`` as fallbacks; mob kills come from
    ``collect_entity_rules``. Computed once and reused for both region placement (create_regions) and
    ``set_rule`` (set_rules), so the two never diverge."""
    helper = RuleHelper(world)
    existing = set(world._get_active_locations())
    curated = collect_advancement_rules(helper)              # by display name
    compiler = TriggerCompiler(helper, frozenset(existing))
    _bacap = overlay_packs().get("blazeandcave")
    manifest = _manifest(_bacap if (world.options.blazeandcave and _bacap) else base_pack())

    rules: dict = {}
    for location_name, loc_data in ADVANCEMENT_LOCATIONS.items():
        if location_name not in existing:
            continue
        record = manifest.get(loc_data.game_id)
        name = location_name.removeprefix(ADVANCEMENT_PREFIX)
        condition = compiler.compile(record) if record is not None else None
        if condition is None:
            condition = curated.get(name)
        if condition is None and record is not None:
            condition = compiler.parent_rule(record)
        if condition is None:
            condition = Const(True)
        rules[location_name] = condition

    for mob_name, condition in collect_entity_rules(helper).items():
        if mob_name not in MOBS_ALL:
            raise KeyError(f"Mob {mob_name} not in MOBS_ALL")
        prefix = BOSS_KILL_PREFIX if MOBS_ALL[mob_name].category == MCEntityCategory.BOSS \
            else ENTITY_KILL_PREFIX
        location_name = f"{prefix}{mob_name}"
        if location_name in existing:
            rules[location_name] = condition
    return rules


# The always-reachable origin region (MCRegion.MENU): a location placed here is gated by its rule
# alone — used for checks a rule pins to no single dimension (multi-dimension ORs, item-only).
MENU_REGION = "Menu"

# Dimensions a location can be placed in, ordered deepest-first: the End is reached via the Overworld
# and the Nether via the Overworld (on an Overworld start), so placing in the deepest required region
# implies the shallower ones through the region graph.
_PLACEMENT_REGIONS = (REGION_END, REGION_NETHER, REGION_OVERWORLD)


def _rule_true_in(node: dict, regions: frozenset, rule_dicts: dict, memo: dict) -> bool:
    """Whether a serialized rule is satisfiable when exactly ``regions`` are reachable and every item
    has been received. ``loc`` (reach-another-advancement) recurses through ``rule_dicts`` (cycles
    resolve pessimistically); an unknown ``loc`` is treated as reachable so we never over-require."""
    k = node["k"]
    if k == "const":
        return node["v"]
    if k == "has":
        return True  # full item pool — we are asking which *regions* the rule needs
    if k == "region":
        return node["r"] in regions
    if k == "loc":
        target = node["l"]
        if target not in rule_dicts:
            return True
        key = (target, regions)
        if key in memo:
            return memo[key]
        memo[key] = False  # break cycles pessimistically
        memo[key] = _rule_true_in(rule_dicts[target], regions, rule_dicts, memo)
        return memo[key]
    if k == "and":
        return all(_rule_true_in(c, regions, rule_dicts, memo) for c in node["c"])
    if k == "or":
        return any(_rule_true_in(c, regions, rule_dicts, memo) for c in node["c"])
    if k == "atleast":
        return sum(1 for c in node["c"] if _rule_true_in(c, regions, rule_dicts, memo)) >= node["n"]
    return False


def derive_location_regions(rules: dict) -> dict:
    """Map each location to the region it should be PLACED in, derived from its rule so placement only
    ever reinforces a dimension the rule already requires — never the spurious uniform-Overworld floor
    that over-gates a Nether/End-native check (and breaks a Nether start). A region is *required* when
    removing it makes the rule unsatisfiable (with all items); the deepest required region wins, and a
    location requiring no single dimension (multi-dimension OR like "kill any mob", or item-only) goes
    to the always-reachable origin so the rule alone gates it."""
    rule_dicts = {name: rule.to_dict() for name, rule in rules.items()}
    full = frozenset(_PLACEMENT_REGIONS)
    placement: dict = {}
    for name, node in rule_dicts.items():
        if not _rule_true_in(node, full, rule_dicts, {}):
            placement[name] = REGION_OVERWORLD  # unsatisfiable even with everything → leave on the floor
            continue
        required = {r for r in _PLACEMENT_REGIONS
                    if not _rule_true_in(node, full - {r}, rule_dicts, {})}
        placement[name] = next((r for r in _PLACEMENT_REGIONS if r in required), MENU_REGION)
    return placement


def set_rules(world) -> None:
    # Locations actually created this seed depend on options (challenge_sanity, kill_sanity).
    existing_locations = {loc.name for loc in world.multiworld.get_locations(world.player)}

    # Reuse the rules computed for region placement (create_regions) so placement and reachability
    # never diverge; fall back to building them here if create_regions didn't (defensive).
    rules = getattr(world, "_location_rules", None) or build_location_rules(world)

    # The captured nodes are the exact same ones handed to set_rule, serialized for the mod.
    exported_rules = {}
    for location_name, condition in rules.items():
        if location_name not in existing_locations:
            continue
        set_rule(world.multiworld.get_location(location_name, world.player), condition)
        exported_rules[location_name] = condition

    # Locations with no explicit rule are always accessible in AP (set_rule was never called
    # for them). Mirror that in the export so they aren't dropped — notably each tab's `root`
    # advancement, which carries no rule but is a real check. Region reachability still applies
    # in build_logic_export, so e.g. the Nether root only counts once the Nether is reached.
    for location_name in existing_locations:
        exported_rules.setdefault(location_name, Const(True))

    world.logic_rules = exported_rules
