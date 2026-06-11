from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def birthday_song(helper: RuleHelper) -> dict:
    return {
        A_BIRTHDAY_SONG: helper.all_of(
            helper.entity(E_ALLAY),
            helper.can_get_cake(),
        )
    }
