from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def bogged(helper: RuleHelper) -> dict:
    return {
        E_BOGGED: helper.entity(E_BOGGED)
    }
