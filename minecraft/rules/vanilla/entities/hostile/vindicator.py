from ....constants import *
from ....helpers import RuleHelper


def vindicator(helper: RuleHelper) -> dict:
    return {
        E_VINDICATOR: helper.entity(E_VINDICATOR)
    }
