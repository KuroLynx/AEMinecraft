from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper

def birthday_song(helper: RuleHelper) -> dict:
    return {
        A_BIRTHDAY_SONG: helper.all_of(
            helper.entity("Allay"),
            helper.can_get_cake(),
        )
    }
