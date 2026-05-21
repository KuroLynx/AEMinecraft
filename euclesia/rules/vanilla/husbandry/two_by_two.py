from worlds.euclesia.data import MOBS_BREEDABLE
from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def two_by_two(helper: RuleHelper) -> dict:
    return {
        A_TWO_BY_TWO: helper.has_all_entities(*MOBS_BREEDABLE.keys())
    }
