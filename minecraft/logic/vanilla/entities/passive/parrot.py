from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def parrot(helper: RuleHelper) -> dict:
    return {
        E_PARROT: helper.entity(E_PARROT)
    }
