from ....constants import *
from ....helpers import RuleHelper


def slime(helper: RuleHelper) -> dict:
    return {
        E_SLIME: helper.entity(E_SLIME)
    }
