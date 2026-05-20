from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def this_boat_has_legs(helper: RuleHelper) -> dict:
    return {
        A_THIS_BOAT_HAS_LEGS: helper.all_of(helper.entity("Strider"), helper.knowledge(K_FISHING))
    }
