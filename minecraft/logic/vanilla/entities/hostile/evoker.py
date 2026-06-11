from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def evoker(helper: RuleHelper) -> dict:
    return {
        E_EVOKER: helper.entity(E_EVOKER)
    }
