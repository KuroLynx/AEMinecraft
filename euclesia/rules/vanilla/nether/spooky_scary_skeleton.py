from ...constants import *
from ...helpers import RuleHelper


def spooky_scary_skeleton(helper: RuleHelper) -> dict:
    return {
        A_SPOOKY_SCARY_SKELETON: helper.entity(E_WITHER_SKELETON),
    }
