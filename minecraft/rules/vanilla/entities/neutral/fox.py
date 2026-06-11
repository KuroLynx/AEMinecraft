from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def fox(helper: RuleHelper) -> dict:
    return {
        E_FOX: helper.entity(E_FOX)
    }
