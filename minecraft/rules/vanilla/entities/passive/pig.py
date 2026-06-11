from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def pig(helper: RuleHelper) -> dict:
    return {
        E_PIG: helper.entity(E_PIG)
    }
