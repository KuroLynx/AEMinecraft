from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def zombie_villager(helper: RuleHelper) -> dict:
    return {
        E_ZOMBIE_VILLAGER: helper.entity(E_ZOMBIE_VILLAGER)
    }
