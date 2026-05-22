from ....constants import *
from ....helpers import RuleHelper


def zombie(helper: RuleHelper) -> dict:
    return {
        E_ZOMBIE: helper.entity(E_ZOMBIE)
    }
