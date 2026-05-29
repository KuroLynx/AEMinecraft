from ...constants import *
from ...helpers import RuleHelper


def this_boat_has_legs(helper: RuleHelper) -> dict:
    return {
        A_THIS_BOAT_HAS_LEGS: helper.all_of(helper.entity(E_STRIDER), helper.knowledge(K_FISHING))
    }
