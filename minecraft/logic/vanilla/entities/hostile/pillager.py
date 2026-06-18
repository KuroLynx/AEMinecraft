from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def pillager(helper: RuleHelper) -> dict:
    return {
        E_PILLAGER: helper.entity(E_PILLAGER)
    }
