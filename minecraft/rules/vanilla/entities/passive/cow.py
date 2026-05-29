from ....constants import *
from ....helpers import RuleHelper


def cow(helper: RuleHelper) -> dict:
    return {
        E_COW: helper.entity(E_COW)
    }
