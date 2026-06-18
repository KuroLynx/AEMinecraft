from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def mooshroom(helper: RuleHelper) -> dict:
    return {
        E_MOOSHROOM: helper.entity(E_MOOSHROOM)
    }
