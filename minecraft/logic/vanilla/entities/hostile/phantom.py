from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def phantom(helper: RuleHelper) -> dict:
    return {
        E_PHANTOM: helper.entity(E_PHANTOM)
    }
