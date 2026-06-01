from ...constants import *
from ...helpers import RuleHelper

def subspace_bubble(helper: RuleHelper) -> dict:
    return {
        # Travel a long Overworld distance via a short Nether trip — inherently needs the Overworld
        # (its region gate already requires the Nether).
        A_SUBSPACE_BUBBLE: helper.access_region(REGION_OVERWORLD)
    }
