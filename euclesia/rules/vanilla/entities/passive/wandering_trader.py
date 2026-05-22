from ....constants import *
from ....helpers import RuleHelper


def wandering_trader(helper: RuleHelper) -> dict:
    return {
        E_WANDERING_TRADER: helper.entity(E_WANDERING_TRADER)
    }
