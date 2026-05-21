from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper



def copper_golem(helper: RuleHelper) -> dict:
    return {
        E_COPPER_GOLEM: helper.entity(E_COPPER_GOLEM)
    }
