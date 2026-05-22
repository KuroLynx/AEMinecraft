from ....constants import *
from ....helpers import RuleHelper


def piglin(helper: RuleHelper) -> dict:
    return {
        E_PIGLIN: helper.entity(E_PIGLIN)
    }
