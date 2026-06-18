from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def bee(helper: RuleHelper) -> dict:
    return {
        E_BEE: helper.entity(E_BEE)
    }
