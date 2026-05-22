from ....constants import *
from ....helpers import RuleHelper


def ghast(helper: RuleHelper) -> dict:
    return {
        E_GHAST: helper.entity(E_GHAST)
    }
