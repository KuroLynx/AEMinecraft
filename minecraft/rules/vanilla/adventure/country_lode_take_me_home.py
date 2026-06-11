from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def country_lode_take_me_home(helper: RuleHelper) -> dict:
    return {
        A_COUNTRY_LODE_TAKE_ME_HOME: helper.all_of(
            # Lodestone — craft or found in helper.structures
            helper.any_of(
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_ACQUIRE_HARDWARE}"),  # craft Lodestone (Iron + Chiseled Stone Brick)
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion Remnant
                helper.any_portal(True),  # Ruined Portal
            ),
            # Compass — craft or found in helper.structures
            helper.any_of(
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_ACQUIRE_HARDWARE}"),  # craft Compass (Iron + Redstone)
                helper.structure(S_ANCIENT_CITY),  # found in chest
                helper.any_shipwreck(),  # found in chest
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # Stronghold
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # found in chest
                helper.can_trade_villager(4),  # Librarian sells a Compass at lvl 4
            ),
        ),
    }
