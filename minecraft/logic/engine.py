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
from .constants import (
    A_ADVENTURE,
    A_HUSBANDRY,
    A_SPOOKY_SCARY_SKELETON,
    ADVANCEMENT_PREFIX,
    E_ELDER_GUARDIAN,
    E_ENDER_DRAGON,
    E_WARDEN,
    E_WITHER,
    K_ARMOR,
    K_BOW,
    MAT_IRON,
)


def collect_advancement_rules(helper: RuleHelper) -> dict:
    """Curated advancement fallbacks. Advancement logic is normally compiled from each advancement's
    criteria (logic/root.py); only the two tab-root advancements, which have no compilable criteria,
    carry an explicit rule here."""
    return {
        A_HUSBANDRY: helper.can_get_food(),
        A_ADVENTURE: helper.has_any_entities(*MOBS_ALL.keys()),
    }


def collect_entity_rules(helper: RuleHelper) -> dict:
    """Kill-location logic for every mob: plain reachability (``entity()``) for all but the four
    bosses, which gate on the gear / knowledge / environment their fight demands."""
    rules = {name: helper.entity(name) for name in MOBS_ALL}
    rules.update(_boss_rules(helper))
    return rules


def _boss_rules(helper: RuleHelper) -> dict:
    """Bespoke kill gates for the four bosses (their kills are goal conditions, so they must not be
    beatable from scratch)."""
    return {
        E_ENDER_DRAGON: helper.all_of(
            helper.entity(E_ENDER_DRAGON),
            helper.knowledge(K_BOW),  # shoot out the end crystals
            helper.any_of(
                helper.can_kill(),
                helper.can_get_bed(),  # bed-bombing strategy
            ),
        ),
        E_WITHER: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_SPOOKY_SCARY_SKELETON}"),  # wither skulls
            helper.entity(E_WITHER),
            helper.can_kill(),
            helper.knowledge(K_ARMOR),  # survive the blast / wither effect
            helper.material(MAT_IRON),  # at least iron-tier gear
        ),
        E_WARDEN: helper.all_of(
            helper.entity(E_WARDEN),
            helper.can_kill(),
        ),
        E_ELDER_GUARDIAN: helper.all_of(
            helper.entity(E_ELDER_GUARDIAN),
            helper.can_kill(),
            helper.can_breath_underwater(),  # survive the fight underwater
        ),
    }
