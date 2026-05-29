from ....constants import *
from ....helpers import RuleHelper


def skeleton_horse(helper: RuleHelper) -> dict:
    return {
        E_SKELETON_HORSE: helper.entity(E_SKELETON_HORSE)
    }
