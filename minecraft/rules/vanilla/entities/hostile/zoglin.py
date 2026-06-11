from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def zoglin(helper: RuleHelper) -> dict:
    return {
        E_ZOGLIN: helper.entity(E_ZOGLIN)
    }
