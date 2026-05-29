from ....constants import *
from ....helpers import RuleHelper


def zombie_horse(helper: RuleHelper) -> dict:
    return {
        E_ZOMBIE_HORSE: helper.entity(E_ZOMBIE_HORSE)
    }
