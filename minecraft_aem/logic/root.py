import json
from importlib.resources import files

from worlds.generic.Rules import set_rule

from ..data import MCEntityCategory
from ..content.registry import overlay_packs
from .acquisition import RuleHelper
from .ast import Const
from .constants import *
from .engine import collect_advancement_rules, collect_entity_rules
from .fixed_point import price_table
from .criteria import CriteriaCompiler


def _manifest(pack_name: str) -> dict:
    """A pack's advancement manifest (tools/extract_manifest.py), keyed by advancement game_id."""
    root = __package__.rsplit(".", 1)[0]  # e.g. "worlds.minecraft_aem"
    with files(root).joinpath("packs", pack_name, "manifest.json").open(encoding="utf-8") as f:
        return json.load(f)


# exploration_logic: what counts as "going somewhere". A location criterion names a place (biome,
# structure, dimension, position) and nothing else; changed_dimension / nether_travel are trips by
# definition. Standing on a block or in the light is not exploring. BACAP adds "not in spectator" to
# every location criterion, which is allowed alongside.
_PLACE_KEYS = frozenset({"biomes", "biome", "structures", "structure", "dimension", "position"})


def _places(cond) -> bool | None:
    """True: the conditions name a place and nothing else. False: they ask for something that isn't a
    place. None: they say nothing about where the player is."""
    if isinstance(cond, list):
        found = [_places(c) for c in cond]
        return False if False in found else (True if True in found else None)
    if not isinstance(cond, dict):
        return False
    kind = cond.get("condition", "").removeprefix("minecraft:")
    if kind in ("any_of", "all_of", "alternative"):
        return _places(cond.get("terms", []))
    if kind == "inverted":
        return None if _places(cond.get("term")) is not False else False   # "not spectator"
    if kind == "location_check":
        return _location_places(cond.get("predicate", {}))
    predicate = cond.get("predicate", {}) if kind == "entity_properties" else cond
    if "condition" in cond and kind != "entity_properties":
        return False
    specific = predicate.get("type_specific", {})
    if set(predicate) - {"location", "type_specific"} or set(specific) - {"type", "gamemode"}:
        return False
    return _location_places(predicate["location"]) if "location" in predicate else None


def _location_places(location: dict) -> bool:
    return bool(location) and set(location) <= _PLACE_KEYS


def is_exploration(record: dict) -> bool:
    """Every criterion of the advancement is a trip (exploration_logic): a biome, structure,
    dimension or distance, with nothing to hold, wear or kill on the way."""
    criteria = record.get("criteria") or {}
    if not criteria:
        return False
    for criterion in criteria.values():
        trigger = (criterion.get("trigger") or "").removeprefix("minecraft:")
        player = (criterion.get("conditions") or {}).get("player")
        if trigger in ("changed_dimension", "nether_travel"):
            if player is not None and _places(player) is False:
                return False
        elif trigger != "location" or player is None or _places(player) is not True:
            return False
    return True


def build_location_rules(world, glitch: bool = False) -> dict:
    """The final reachability rule for every active location this seed (advancements + mob/boss kills),
    keyed by location name. Advancement logic is derived from the criteria (CriteriaCompiler), with the
    curated rule, then the parent chain, then ``Const(True)`` as fallbacks; mob kills come from
    ``collect_entity_rules``. Computed once and reused for both region placement (create_regions) and
    ``set_rule`` (set_rules), so the two never diverge.

    ``glitch=True`` builds the permissive twin instead: same locations, but the unreliable alternates
    strict logic drops are kept (see RuleHelper._demote), and BACAP rewards join as ``loc`` nodes.
    Nothing fills against it — it ships in slot_data so the mod can colour a tile GLITCHABLE. Its
    rules can name item prices (ast.PriceRef); the table they resolve against is left on
    ``world._glitch_prices`` for the export."""
    helper = RuleHelper(world, glitch=glitch)
    existing = set(world._get_active_locations())
    curated = collect_advancement_rules(helper)              # by display name
    content = world.content
    _bacap = overlay_packs(content.version).get("blazeandcave")
    manifest = _manifest(_bacap if (world.options.blazeandcave and _bacap) else content.BASE_PACK)
    compiler = CriteriaCompiler(helper, frozenset(existing), records=manifest)
    # exploration_logic: a trip is planned only with food to last it (strict graph only).
    supplies = (helper.strict_only(helper.all_of(helper.has_food_supply(), helper.slot_group("carry")))
                if world.options.exploration_logic else None)

    rules: dict = {}
    for location_name, loc_data in content.ADVANCEMENT_LOCATIONS.items():
        if location_name not in existing:
            continue
        record = manifest.get(loc_data.game_id)
        name = location_name.removeprefix(ADVANCEMENT_PREFIX)
        condition = compiler.compile(record, loc_data.game_id) if record is not None else None
        if condition is None:
            condition = curated.get(name)
        if condition is None and record is not None:
            condition = compiler.parent_rule(record, loc_data.game_id)
        if condition is None:
            condition = Const(True)
        if supplies is not None and record is not None and is_exploration(record):
            condition = helper.all_of(condition, supplies)
        rules[location_name] = condition

    for mob_name, condition in collect_entity_rules(helper).items():
        if mob_name not in content.MOBS_ALL:
            raise KeyError(f"Mob {mob_name} not in MOBS_ALL ({content.version})")
        prefix = BOSS_KILL_PREFIX if content.MOBS_ALL[mob_name].category == MCEntityCategory.BOSS \
            else ENTITY_KILL_PREFIX
        location_name = f"{prefix}{mob_name}"
        if location_name in existing:
            rules[location_name] = condition

    if glitch:
        world._glitch_prices = price_table(helper)
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
