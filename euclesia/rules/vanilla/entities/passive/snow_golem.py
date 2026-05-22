from ....constants import *
from ....helpers import RuleHelper


def snow_golem(helper: RuleHelper) -> dict:
    return {
        E_SNOW_GOLEM: helper.entity(E_SNOW_GOLEM)
    }
