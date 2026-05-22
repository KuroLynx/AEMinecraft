from ....constants import *
from ....helpers import RuleHelper



def chicken(helper: RuleHelper) -> dict:
    return {
        E_CHICKEN: helper.entity(E_CHICKEN)
    }
