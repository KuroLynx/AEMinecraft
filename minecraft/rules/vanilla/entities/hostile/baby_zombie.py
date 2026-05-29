from ....constants import *
from ....helpers import RuleHelper


def baby_zombie(helper: RuleHelper) -> dict:
    return {
        E_BABY_ZOMBIE: helper.entity(E_BABY_ZOMBIE)
    }
