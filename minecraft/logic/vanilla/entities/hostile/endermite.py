from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def endermite(helper: RuleHelper) -> dict:
    return {
        E_ENDERMITE: helper.entity(E_ENDERMITE)
    }
