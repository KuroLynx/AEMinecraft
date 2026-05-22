from ....constants import *
from ....helpers import RuleHelper


def shulker(helper: RuleHelper) -> dict:
    return {
        E_SHULKER: helper.entity(E_SHULKER)
    }
