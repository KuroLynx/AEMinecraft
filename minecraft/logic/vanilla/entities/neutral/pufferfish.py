from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def pufferfish(helper: RuleHelper) -> dict:
    return {
        E_PUFFERFISH: helper.entity(E_PUFFERFISH)
    }
