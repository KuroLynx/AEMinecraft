from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def tadpole(helper: RuleHelper) -> dict:
    return {
        E_TADPOLE: helper.entity(E_TADPOLE)
    }
