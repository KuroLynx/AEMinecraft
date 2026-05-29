from ....data import MOBS_ALL
from ...constants import *
from ...helpers import RuleHelper


def over_overkill(helper: RuleHelper) -> dict:
    return {
        A_OVER_OVERKILL: helper.all_of(
            helper.knowledge(K_MACE),
            helper.knowledge(K_ENCHANT),
            helper.entity(E_BREEZE),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),
            helper.has_any_entities(*[name for name in MOBS_ALL.keys()])
        )
    }
