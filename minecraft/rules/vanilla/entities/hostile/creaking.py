from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def creaking(helper: RuleHelper) -> dict:
    return {
        E_CREAKING: helper.entity(E_CREAKING)
    }
