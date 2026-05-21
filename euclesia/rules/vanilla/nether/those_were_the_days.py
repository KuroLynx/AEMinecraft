from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper

def those_were_the_days(helper: RuleHelper) -> dict:
    return {
        A_THOSE_WERE_THE_DAYS: helper.structure("Bastion Remnant")
    }
