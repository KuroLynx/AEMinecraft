from ....data import MOBS_ALL, MOBS_BOSS
from ...constants import *
from ...helpers import RuleHelper


def arbalistic(helper: RuleHelper) -> dict:
    return {
        A_ARBALISTIC: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_OL_BETSY}"),
            helper.knowledge(K_ENCHANT),
            helper.has_all_entities(*[name for name in MOBS_ALL.keys() if name not in MOBS_BOSS]),
        ),
    }
