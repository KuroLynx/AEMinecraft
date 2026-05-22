from ....constants import *
from ....helpers import RuleHelper


def mule(helper: RuleHelper) -> dict:
    return {
        E_MULE: helper.entity(E_MULE)
    }
