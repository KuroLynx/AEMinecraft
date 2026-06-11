from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def rabbit(helper: RuleHelper) -> dict:
    return {
        E_RABBIT: helper.entity(E_RABBIT)
    }
