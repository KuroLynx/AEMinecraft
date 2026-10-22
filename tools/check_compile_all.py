"""Compile every advancement of every manifest and fail on any predicate the compiler can't read.

The trigger compiler raises CompileError on a field present in a shape it doesn't understand (a list
where it reads a string, a tag it can't resolve), instead of quietly pinning nothing. This runs that
over the vanilla pack and the BACAP overlay of each Minecraft version, then feeds it one deliberately
malformed predicate to prove the error names the advancement and the field.

    python tools/check_compile_all.py                  # 26.1.2, 26.2 and 26.3
    python tools/check_compile_all.py 26.3             # one version
"""
import os
import sys

AP_ROOT = os.environ.get("AP_ROOT", r"C:\Users\benja\PycharmProjects\ArchipelagoClone")
sys.path.insert(0, AP_ROOT)
os.chdir(AP_ROOT)

import logging  # noqa: E402
logging.disable(logging.WARNING)

from test.general import setup_multiworld  # noqa: E402
from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from worlds.minecraft_aem.content.registry import overlay_packs  # noqa: E402
from worlds.minecraft_aem.logic.acquisition import RuleHelper  # noqa: E402
from worlds.minecraft_aem.logic.criteria import CompileError, CriteriaCompiler  # noqa: E402
from worlds.minecraft_aem.logic.root import _manifest  # noqa: E402

VERSIONS = ("26.1.2", "26.2", "26.3")
WORLD = AutoWorldRegister.world_types["AEMinecraft"]


def _world(options: dict):
    """A world to compile against. Its own generation compiles every advancement too, so an
    unreadable one would stop it before the report below lists them all: tolerate them here."""
    strict = CriteriaCompiler.compile

    def tolerant(self, record, gid=None):
        try:
            return strict(self, record, gid)
        except CompileError:
            return None

    CriteriaCompiler.compile = tolerant
    try:
        return setup_multiworld(WORLD, options=options).worlds[1]
    finally:
        CriteriaCompiler.compile = strict


def check(version: str) -> int:
    world = _world({"blazeandcave": 1, "minecraft_version": version})
    packs = [world.content.BASE_PACK]
    bacap = overlay_packs(version).get("blazeandcave")
    if bacap:
        packs.append(bacap)
    failures = 0
    for pack in packs:
        records = _manifest(pack)
        compiler = CriteriaCompiler(RuleHelper(world), None, records=records)
        errors = []
        for gid, record in records.items():
            try:
                compiler.compile(record, gid)
            except CompileError as error:
                errors.append(str(error))
        failures += len(errors)
        print(f"{version} {pack}: {len(records)} advancements, {len(errors)} unreadable")
        for line in errors:
            print("   ", line)
    return failures


def malformed_raises(version: str) -> bool:
    """A list of numbers where a structure id belongs must raise, naming the advancement and field."""
    world = _world({"minecraft_version": version})
    compiler = CriteriaCompiler(RuleHelper(world), None, records={})
    record = {"criteria": {"c": {"trigger": "minecraft:location", "conditions": {"player": {
        "location": {"structures": [1, 2]}}}}}}
    try:
        compiler.compile(record, "test:malformed")
    except CompileError as error:
        ok = "test:malformed" in str(error) and "location.structures" in str(error)
        print(f"{version} malformed predicate raised: {error}" + ("" if ok else "  <-- missing name or path"))
        return ok
    print(f"{version} malformed predicate did NOT raise")
    return False


def main() -> int:
    versions = sys.argv[1:] or VERSIONS
    failures = sum(check(v) for v in versions)
    malformed_ok = all(malformed_raises(v) for v in versions)
    print("RESULT:", "PASS" if failures == 0 and malformed_ok else "FAIL")
    return 0 if failures == 0 and malformed_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
