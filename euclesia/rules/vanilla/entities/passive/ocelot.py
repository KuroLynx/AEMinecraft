from ....constants import *
from ....helpers import RuleHelper


def ocelot(helper: RuleHelper) -> dict:
    return {
        E_OCELOT: helper.entity(E_OCELOT)
    }
