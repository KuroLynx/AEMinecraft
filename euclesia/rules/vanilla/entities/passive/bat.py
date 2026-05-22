from ....constants import *
from ....helpers import RuleHelper



def bat(helper: RuleHelper) -> dict:
    return {
        E_BAT: helper.entity(E_BAT)
    }
