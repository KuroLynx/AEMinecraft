from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def donkey(helper: RuleHelper) -> dict:
    return {
        E_DONKEY: helper.entity(E_DONKEY)
    }
