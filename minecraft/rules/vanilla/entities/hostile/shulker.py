from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def shulker(helper: RuleHelper) -> dict:
    return {
        E_SHULKER: helper.entity(E_SHULKER)
    }
