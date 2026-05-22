from ....constants import *
from ....helpers import RuleHelper



def camel_husk(helper: RuleHelper) -> dict:
    return {
        E_CAMEL_HUSK: helper.entity(E_CAMEL_HUSK)
    }
