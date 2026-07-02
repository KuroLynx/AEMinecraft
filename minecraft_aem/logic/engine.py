"""Rule collectors for the two location families that aren't compiled from advancement criteria.

Advancement logic is derived from each advancement's Minecraft criteria by the trigger compiler
(logic/triggers.py, driven by the pack manifest) — see logic/root.py. So there are no longer any
hand-written per-advancement rule files; this module only supplies:

* **entity rules** — the "Kill Entity:" / "Kill Boss:" locations, which are custom AP locations with
  no advancement manifest to compile. Almost every mob reduces to plain reachability (``entity()``);
  the four bosses carry bespoke gates (gear / knowledge / environment) since their kill is a goal
  condition.
* **the two tab-root advancements** (Husbandry, Adventure) that carry an explicit rule rather than
  compilable criteria; they are offered to root.py as curated fallbacks.
"""
from __future__ import annotations

from ..data import MOBS_ALL
from .acquisition import RuleHelper
from .constants import A_ADVENTURE, A_HUSBANDRY


def collect_advancement_rules(helper: RuleHelper) -> dict:
    """Curated advancement fallbacks. Advancement logic is normally compiled from each advancement's
    criteria (logic/root.py); only the two tab-root advancements, which have no compilable criteria,
    carry an explicit rule here."""
    return {
        A_HUSBANDRY: helper.can_get_food(),
        A_ADVENTURE: helper.has_any_entities(*MOBS_ALL.keys()),
    }


def collect_entity_rules(helper: RuleHelper) -> dict:
    """Kill-location logic for every mob. Delegated to ``RuleHelper.can_defeat`` — plain reachability
    for ordinary mobs, bespoke gates for the four bosses — so the boss kill logic has a single home
    shared with boss-drop resolution in acquire() (e.g. the Wither's nether star)."""
    return {name: helper.can_defeat(name) for name in MOBS_ALL}
