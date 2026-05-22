from ....constants import *
from ....helpers import RuleHelper


def pig(helper: RuleHelper) -> dict:
    return {
        E_PIG: helper.entity(E_PIG)
    }
