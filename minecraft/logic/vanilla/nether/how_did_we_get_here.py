from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def how_did_we_get_here(helper: RuleHelper) -> dict:
    return {
        A_HOW_DID_WE_GET_HERE: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_A_FURIOUS_COCKTAIL}"),  # Covers: Fire Resistance, Infestation, Invisibility,
            # Jump Boost, Night Vision, Oozing, Poison, Regeneration,
            # Resistance, Slow Falling, Slowness, Speed, Strength,
            # Water Breathing, Weakness, Weaving, Wind Charged

            # Absorption — Golden Apple, Enchanted Golden Apple or Totem of Undying
            # Already specified by A Furious Cocktail indirectly

            # Bad Omen — Pillager Raid Captain
            helper.entity(E_PILLAGER),

            # Blindness — Suspicious Stew with Azure Bluet (always accessible)
            # Hunger — Pufferfish or Rotten Flesh (always accessible)
            # Nausea — Pufferfish (always accessible)

            # Breath of the Nautilus + Conduit Power — Nautilus Shell + Heart of the Sea
            helper.all_of(
                helper.entity(E_DROWNED),  # Nautilus Shell
                helper.structure(S_BURIED_TREASURE),  # Heart of the Sea
            ),

            # Darkness — Warden proximity
            helper.entity(E_WARDEN),

            # Dolphin's Grace — swim near a Dolphin
            helper.entity(E_DOLPHIN),

            # Glowing — Spectral Arrow (Glowstone always accessible in Nether)
            helper.all_of(
                helper.knowledge(K_BOW),  # need bow/crossbow to shoot
                helper.any_of(
                    # Glowstone + Arrow — always accessible in Nether
                    helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion Remnant chests
                    helper.can_barter(),  # Piglin bartering
                ),
            ),

            # Haste — Beacon level 2
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_BEACONATOR}"),

            # {A_HERO_OF_THE_VILLAGE} — complete a Raid
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_HERO_OF_THE_VILLAGE}"),

            # Infested — Stone always accessible, no condition needed

            # Levitation — Shulker attack
            helper.entity(E_SHULKER),

            # Mining Fatigue — Elder Guardian
            helper.entity(E_ELDER_GUARDIAN),

            # Raid Omen — Ominous Bottle near a village
            helper.any_village(),  # village needed for Raid Omen

            # Trial Omen — already covered by A Furious Cocktail (Trial Chambers + Pillager)

            # Wither — Wither Rose or Wither Skeleton arrow
            helper.any_of(
                helper.entity(E_WITHER),  # Wither Rose
                helper.entity(E_WITHER_SKELETON),  # Wither Skeleton arrow
            ),
        ),
    }
