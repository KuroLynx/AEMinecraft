from ....constants import *
from ....helpers import RuleHelper


def skeleton(helper: RuleHelper) -> dict:
    return {
        E_SKELETON: helper.entity(E_SKELETON)
    }
