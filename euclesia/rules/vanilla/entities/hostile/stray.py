from ....constants import *
from ....helpers import RuleHelper


def stray(helper: RuleHelper) -> dict:
    return {
        E_STRAY: helper.entity(E_STRAY)
    }
