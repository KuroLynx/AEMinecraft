from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def panda(helper: RuleHelper) -> dict:
    return {
        E_PANDA: helper.entity(E_PANDA)
    }
