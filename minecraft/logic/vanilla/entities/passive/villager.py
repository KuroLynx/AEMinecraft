from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def villager(helper: RuleHelper) -> dict:
    return {
        E_VILLAGER: helper.entity(E_VILLAGER)
    }
