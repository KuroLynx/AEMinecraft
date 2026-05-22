from ....constants import *
from ....helpers import RuleHelper


def wolf(helper: RuleHelper) -> dict:
    return {
        E_WOLF: helper.entity(E_WOLF)
    }
