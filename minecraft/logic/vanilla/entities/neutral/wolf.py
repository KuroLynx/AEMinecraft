from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def wolf(helper: RuleHelper) -> dict:
    return {
        E_WOLF: helper.entity(E_WOLF)
    }
