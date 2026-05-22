from ....constants import *
from ....helpers import RuleHelper


def warden(helper: RuleHelper) -> dict:
    return {
        E_WARDEN: helper.entity(E_WARDEN)
    }
