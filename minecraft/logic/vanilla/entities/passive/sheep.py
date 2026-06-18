from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def sheep(helper: RuleHelper) -> dict:
    return {
        E_SHEEP: helper.entity(E_SHEEP)
    }
