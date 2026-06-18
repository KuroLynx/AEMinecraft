from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def hot_stuff(helper: RuleHelper) -> dict:
    return {
        A_HOT_STUFF: helper.can_craft_bucket(),  # fill a bucket with lava
    }
