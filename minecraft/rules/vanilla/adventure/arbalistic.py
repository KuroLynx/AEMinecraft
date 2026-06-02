from ....data import MOBS_ALL
from ...constants import *
from ...helpers import RuleHelper


def arbalistic(helper: RuleHelper) -> dict:
    # Kill 5 unique mobs with one piercing crossbow shot: crossbow + Piercing (enchant) + any
    # 5 distinct, commonly co-spawning overworld hostiles lined up.
    return {
        A_ARBALISTIC: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_OL_BETSY}"),
            helper.knowledge(K_ENCHANT),
            helper.has_n_entities(5, *MOBS_ALL.keys()),
        ),
    }
