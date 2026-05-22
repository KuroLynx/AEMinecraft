from ....constants import *
from ....helpers import RuleHelper


def ravager(helper: RuleHelper) -> dict:
    return {
        E_RAVAGER: helper.entity(E_RAVAGER)
    }
