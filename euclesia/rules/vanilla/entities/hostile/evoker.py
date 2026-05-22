from ....constants import *
from ....helpers import RuleHelper


def evoker(helper: RuleHelper) -> dict:
    return {
        E_EVOKER: helper.entity(E_EVOKER)
    }
