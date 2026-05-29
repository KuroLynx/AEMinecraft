from ....data import MOBS_ALL, MOBS_BOSS
from ...constants import *
from ...helpers import RuleHelper


def mob_kabob(helper: RuleHelper) -> dict:
    return {
        A_MOB_KABOB: helper.all_of(
            helper.knowledge(K_SPEAR),
            helper.has_any_entities(*[name for name in MOBS_ALL.keys() if name not in MOBS_BOSS]),
        )
    }
