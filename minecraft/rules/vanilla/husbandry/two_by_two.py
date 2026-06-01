from ....data import MOBS_BREEDABLE
from ...constants import *
from ...helpers import RuleHelper


def two_by_two(helper: RuleHelper) -> dict:
    return {
        A_TWO_BY_TWO: helper.all_of(*[helper.can_breed(mob) for mob in MOBS_BREEDABLE.keys()])
    }
