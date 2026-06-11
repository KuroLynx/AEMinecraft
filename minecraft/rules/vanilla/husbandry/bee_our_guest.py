from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def bee_our_guest(helper: RuleHelper) -> dict:
    return {
        A_BEE_OUR_GUEST: helper.entity(E_BEE)
    }
