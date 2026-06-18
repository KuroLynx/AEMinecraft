from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def guardian(helper: RuleHelper) -> dict:
    return {
        E_GUARDIAN: helper.entity(E_GUARDIAN)
    }
