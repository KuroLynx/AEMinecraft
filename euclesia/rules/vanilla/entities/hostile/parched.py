from ....constants import *
from ....helpers import RuleHelper


def parched(helper: RuleHelper) -> dict:
    return {
        E_PARCHED: helper.entity(E_PARCHED)
    }
