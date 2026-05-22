from ....constants import *
from ....helpers import RuleHelper


def wither_skeleton(helper: RuleHelper) -> dict:
    return {
        E_WITHER_SKELETON: helper.entity(E_WITHER_SKELETON)
    }
