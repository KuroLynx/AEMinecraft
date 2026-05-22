from ....constants import *
from ....helpers import RuleHelper


def zombie_nautilus(helper: RuleHelper) -> dict:
    return {
        E_ZOMBIE_NAUTILUS: helper.entity(E_ZOMBIE_NAUTILUS)
    }
