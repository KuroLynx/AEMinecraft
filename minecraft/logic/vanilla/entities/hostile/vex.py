from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def vex(helper: RuleHelper) -> dict:
    return {
        E_VEX: helper.entity(E_VEX)
    }
