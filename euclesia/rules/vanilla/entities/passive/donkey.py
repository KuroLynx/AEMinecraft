from ....constants import *
from ....helpers import RuleHelper


def donkey(helper: RuleHelper) -> dict:
    return {
        E_DONKEY: helper.entity(E_DONKEY)
    }
