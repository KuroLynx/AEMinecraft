from ...constants import *
from ...helpers import RuleHelper

def isnt_it_scute(helper: RuleHelper) -> dict:
    return {
        A_ISNT_IT_SCUTE: helper.all_of(helper.entity("Armadillo"), helper.has_brush())
    }
