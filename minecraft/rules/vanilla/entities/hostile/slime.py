from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def slime(helper: RuleHelper) -> dict:
    return {
        E_SLIME: helper.entity(E_SLIME)
    }
