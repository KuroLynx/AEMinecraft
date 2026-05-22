from ....constants import *
from ....helpers import RuleHelper



def cat(helper: RuleHelper) -> dict:
    return {
        E_CAT: helper.entity(E_CAT)
    }
