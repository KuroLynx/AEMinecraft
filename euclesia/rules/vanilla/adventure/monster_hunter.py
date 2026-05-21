from worlds.euclesia.data import MOBS_HOSTILE
from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def monster_hunter(helper: RuleHelper) -> dict:
    return {
        A_MONSTER_HUNTER: helper.has_any_entities(*MOBS_HOSTILE.keys())
    }
