from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def dolphin(helper: RuleHelper) -> dict:
    return {
        E_DOLPHIN: helper.entity(E_DOLPHIN)
    }
