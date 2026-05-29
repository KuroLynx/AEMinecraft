from ....constants import *
from ....helpers import RuleHelper


def cod(helper: RuleHelper) -> dict:
    return {
        E_COD: helper.entity(E_COD)
    }
