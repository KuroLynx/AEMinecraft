from ....constants import *
from ....helpers import RuleHelper


def zombie_villager(helper: RuleHelper) -> dict:
    return {
        E_ZOMBIE_VILLAGER: helper.entity(E_ZOMBIE_VILLAGER)
    }
