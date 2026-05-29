from ....constants import *
from ....helpers import RuleHelper


def husk(helper: RuleHelper) -> dict:
    return {
        E_HUSK: helper.entity(E_HUSK)
    }
