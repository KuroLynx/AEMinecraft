from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def this_boat_has_legs(helper: RuleHelper) -> dict:
    return {
        A_THIS_BOAT_HAS_LEGS: helper.all_of(helper.entity(E_STRIDER), helper.knowledge(K_FISHING))
    }
