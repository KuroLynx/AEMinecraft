from ....constants import *
from ....helpers import RuleHelper


def phantom(helper: RuleHelper) -> dict:
    return {
        E_PHANTOM: helper.entity(E_PHANTOM)
    }
