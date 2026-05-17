from dataclasses import dataclass

from Options import Choice, OptionSet, Toggle, Range, NumericOption, PerGameCommonOptions


class Goals(Choice):
    """"""
    display_name = "Goals"
    option_vanilla = 0
    option_all_bosses = 1
    default = 0


class BossSelection(OptionSet):
    """ Define all bosses that should be killed when all bosses or random bosses is selected"""
    display_name = "Bosses"
    valid_keys = {"Ender Dragon", "Elder Guardian", "Warden", "Wither"}
    default = {"Ender Dragon", "Elder Guardian", "Warden", "Wither"}


class DeathList(Toggle):
    """ Toggling on will have you killed certain mobs to complete your game"""
    display_name = "Death List"
    option_true = 1
    option_false = 0
    default = 0


class DeathListCount(Range):
    """Define how many mobs you should be tasked to kill with 'Death List' toggled on"""
    display_name = "Death List Count"
    range_start = 1
    range_end = 87
    default = 42


class AdvancementsRequired(Range):
    """Define how many advancements you need to complete your game"""
    display_name = "Advancements Required"
    range_start = 0
    range_end = 160
    default = 80


class DeathLink(Toggle):
    """Should you die, you will cause death to all, and should one die, you will soon follow"""
    display_name = "Death Link"
    option_true = 1
    option_false = 0
    default = 0


class VillagerTrust(Toggle):
    """Lock the ability to trade with each level of villagers, each will be added to the item pool"""
    display_name = "Villager Trust"
    option_true = 1
    option_false = 0
    default = 0

class KillSanity(Toggle):
    """If toggled on, killing a mob for the first time will send a check
    Bosses will always send a check when killed even if toggled off
    """
    display_name = "Kill Sanity"
    option_true = 1
    option_false = 0
    default = 0

class MobSpawnLockCategory(OptionSet):
    """
    Define which mob categories are locked until their unlock item is received.
    Leave empty to disable  mob spawn locking entirely.
    """
    display_name = "Mob Spawn Lock Category"
    valid_keys = {"passive", "neutral", "hostile"}
    default = frozenset()

@dataclass
class EuclesiaOptions(PerGameCommonOptions):
    goals: Goals
    boss_selection: BossSelection
    death_list: DeathList
    death_list_count: DeathListCount
    advancements_required: AdvancementsRequired
    death_link: DeathLink
    villager_trust: VillagerTrust
    kill_sanity: KillSanity
    mob_spawn_lock_category: MobSpawnLockCategory