from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def blaze(helper: RuleHelper) -> dict:
    return {
        E_BLAZE: helper.entity(E_BLAZE)
    }
