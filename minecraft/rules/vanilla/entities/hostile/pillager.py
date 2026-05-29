from ....constants import *
from ....helpers import RuleHelper


def pillager(helper: RuleHelper) -> dict:
    return {
        E_PILLAGER: helper.entity(E_PILLAGER)
    }
