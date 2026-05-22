from ....constants import *
from ....helpers import RuleHelper


def zoglin(helper: RuleHelper) -> dict:
    return {
        E_ZOGLIN: helper.entity(E_ZOGLIN)
    }
