from ....constants import *
from ....helpers import RuleHelper


def villager(helper: RuleHelper) -> dict:
    return {
        E_VILLAGER: helper.entity(E_VILLAGER)
    }
