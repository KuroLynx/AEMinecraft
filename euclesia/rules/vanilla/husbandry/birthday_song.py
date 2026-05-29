from ...constants import *
from ...helpers import RuleHelper

def birthday_song(helper: RuleHelper) -> dict:
    return {
        A_BIRTHDAY_SONG: helper.all_of(
            helper.entity(E_ALLAY),
            helper.can_get_cake(),
        )
    }
