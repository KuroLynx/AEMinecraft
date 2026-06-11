from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def cod(helper: RuleHelper) -> dict:
    return {
        E_COD: helper.entity(E_COD)
    }
