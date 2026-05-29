from ....constants import *
from ....helpers import RuleHelper


def silverfish(helper: RuleHelper) -> dict:
    return {
        E_SILVERFISH: helper.entity(E_SILVERFISH)
    }
