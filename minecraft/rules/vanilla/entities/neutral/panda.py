from ....constants import *
from ....helpers import RuleHelper


def panda(helper: RuleHelper) -> dict:
    return {
        E_PANDA: helper.entity(E_PANDA)
    }
