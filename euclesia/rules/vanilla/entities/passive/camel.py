from ....constants import *
from ....helpers import RuleHelper



def camel(helper: RuleHelper) -> dict:
    return {
        E_CAMEL: helper.entity(E_CAMEL)
    }
