from ....constants import *
from ....helpers import RuleHelper


def rabbit(helper: RuleHelper) -> dict:
    return {
        E_RABBIT: helper.entity(E_RABBIT)
    }
