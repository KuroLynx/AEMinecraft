from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper



def camel(helper: RuleHelper) -> dict:
    return {
        E_CAMEL: helper.entity(E_CAMEL)
    }
