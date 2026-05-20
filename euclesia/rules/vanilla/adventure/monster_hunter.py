from euclesia import MOBS_HOSTILE
from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def monster_hunter(helper: RuleHelper) -> dict:
    return {
        A_MONSTER_HUNTER: helper.has_any_entities(*MOBS_HOSTILE.keys())
    }
