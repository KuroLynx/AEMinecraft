from ....constants import *
from ....helpers import RuleHelper


def fox(helper: RuleHelper) -> dict:
    return {
        E_FOX: helper.entity(E_FOX)
    }
