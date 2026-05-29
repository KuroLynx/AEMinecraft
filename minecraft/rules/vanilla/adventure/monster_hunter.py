from ....data import MOBS_HOSTILE
from ...constants import *
from ...helpers import RuleHelper


def monster_hunter(helper: RuleHelper) -> dict:
    return {
        A_MONSTER_HUNTER: helper.has_any_entities(*MOBS_HOSTILE.keys())
    }
