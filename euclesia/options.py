from dataclasses import dataclass

from Options import OptionSet, PerGameCommonOptions, Range, Toggle


class BossSelectionMode(Range):
    """How many bosses from Boss List you must defeat to complete your game.

    Use a number from 1 to 4 for an exact count.
    Use 4 to require all bosses in Boss List.
    Use weighted values to randomize the count at generation time.

    Examples:
        1: 100                 → always exactly 1 boss
        4: 100                 → always all bosses in Boss List
        random: 100            → random between 1 and 4
        random-range-1-3: 100  → random between 1 and 3
    """
    display_name = "Boss Selection Mode"
    range_start = 1
    range_end = 4
    default = 4


class BossList(OptionSet):
    """The pool of bosses available for selection.

    The number of bosses actually required is determined by Boss Selection Mode.

    Valid values: Ender Dragon, Elder Guardian, Warden, Wither
    """
    display_name = "Boss List"
    valid_keys = {"Ender Dragon", "Elder Guardian", "Warden", "Wither"}
    default = frozenset({"Ender Dragon", "Elder Guardian", "Warden", "Wither"})


class AdvancementsRequired(Range):
    """Number of advancements you must complete as an additional victory condition.

    Set to 0 to disable this condition entirely.
    This is cumulative with the boss kill condition: all active conditions must be met to win.

    Minimum value is 0
    Maximum value is 125 (the total number of advancements)
    """
    display_name = "Advancements Required"
    range_start = 0
    range_end = 125
    default = 0


class DeathLink(Toggle):
    """When you die, all players with Death Link enabled die too.

    Of course the reverse is true too: when any of them dies, you die.
    """
    display_name = "Death Link"
    option_true = 1
    option_false = 0
    default = 0


class VillagerTrust(Toggle):
    """If enabled, trading with villagers requires receiving Progressive Villager Trust items.

    One item per trade level, 5 levels total.
    These items are added to the multiworld item pool.
    """
    display_name = "Villager Trust"
    option_true = 1
    option_false = 0
    default = 0


class KillSanity(Toggle):
    """If enabled, killing a mob for the first time sends a location check.

    Bosses always send a check regardless of this setting.
    """
    display_name = "Kill Sanity"
    option_true = 1
    option_false = 0
    default = 0


class MobSpawnLockCategory(OptionSet):
    """Define which mob categories are locked until their unlock item is received.

    When a mob category is locked, mobs of that type will not spawn in the world
    until the corresponding 'Entity Unlock: <mob>' item is received from the multiworld.

    Leave empty to disable mob spawn locking entirely.

    Valid values: passive, neutral, hostile

    Examples:
        Lock only hostiles:
            - hostile

        Lock everything:
            - passive
            - neutral
            - hostile
    """
    display_name = "Mob Spawn Lock Category"
    valid_keys = {"passive", "neutral", "hostile"}
    default = frozenset()


class TrapChance(Range):
    """The probability for each filler item to be replaced with a trap item.
    Set to 0 to disable traps entirely.

    Minimum value is 0
    Maximum value is 100
    """
    display_name = "Trap Chance"
    range_start = 0
    range_end = 100
    default = 30


class ChallengeSanity(Toggle):
    """If enabled, includes extremely complex advancements in the location pool.

    This includes:
    - A Furious Cocktail (have all potion effects simultaneously)
    - How Did We Get Here? (have all status effects simultaneously)
    - Arbalistic (Kill 5 unique mobs with one arrow)
    - A Balanced Diet (Eat every food items)

    These advancements are very difficult to complete and are excluded by default.
    """
    display_name = "Challenge Sanity"
    option_true = 1
    option_false = 0
    default = 0


@dataclass
class MCOptions(PerGameCommonOptions):
    boss_selection_mode: BossSelectionMode
    boss_list: BossList
    advancements_required: AdvancementsRequired
    death_link: DeathLink
    villager_trust: VillagerTrust
    kill_sanity: KillSanity
    mob_spawn_lock_category: MobSpawnLockCategory
    trap_chance: TrapChance
    challenge_sanity: ChallengeSanity
