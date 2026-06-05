from ...constants import *
from ...helpers import RuleHelper

def we_need_to_go_deeper(helper: RuleHelper) -> dict:
    # Earned by travelling through a portal *into* the Nether, which requires having reached both
    # dimensions:
    #   - Overworld start: the Nether is the far side -> reduces to access_region(NETHER).
    #   - Nether start: you spawn in the Nether and the start placement no longer auto-grants this
    #     (the changed_dimension trigger is suppressed for it, see ChangeDimensionTriggerMixin), so you
    #     must reach the Overworld and come back -> reduces to access_region(OVERWORLD).
    # access_region of the dimension you start in is a no-op, so this is correct for either start.
    return {
        A_WE_NEED_TO_GO_DEEPER: helper.all_of(
            helper.access_region(REGION_NETHER),
            helper.access_region(REGION_OVERWORLD),
        )
    }
