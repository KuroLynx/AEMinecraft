from ....constants import *
from ....helpers import RuleHelper


def hoglin(helper: RuleHelper) -> dict:
    return {
        E_HOGLIN: helper.entity(E_HOGLIN)
    }
