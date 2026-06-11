from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def mule(helper: RuleHelper) -> dict:
    return {
        E_MULE: helper.entity(E_MULE)
    }
