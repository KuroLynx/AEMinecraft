from ....constants import *
from ....helpers import RuleHelper


def parrot(helper: RuleHelper) -> dict:
    return {
        E_PARROT: helper.entity(E_PARROT)
    }
