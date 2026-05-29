from ....constants import *
from ....helpers import RuleHelper


def horse(helper: RuleHelper) -> dict:
    return {
        E_HORSE: helper.entity(E_HORSE)
    }
