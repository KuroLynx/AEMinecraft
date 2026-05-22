from ....constants import *
from ....helpers import RuleHelper


def wither(helper: RuleHelper) -> dict:
    return {
        E_WITHER: helper.entity(E_WITHER)
    }
