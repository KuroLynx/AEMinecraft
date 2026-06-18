from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def monsters_hunted(helper: RuleHelper) -> dict:
    return {
        A_MONSTERS_HUNTED: helper.all_of(
            helper.has_all_entities(*[n for n in MOBS_HOSTILE.keys() if n != "Warden"]),
            helper.entity(E_ENDER_DRAGON),
            helper.entity(E_WITHER),
        ),
    }
