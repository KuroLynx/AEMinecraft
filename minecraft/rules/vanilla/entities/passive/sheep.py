from ....constants import *
from ....helpers import RuleHelper


def sheep(helper: RuleHelper) -> dict:
    return {
        E_SHEEP: helper.entity(E_SHEEP)
    }
