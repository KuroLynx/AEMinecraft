"""One-shot: collapse deep constants/helpers/data import chains in the rule modules into a
single ``from .. import *`` (which resolves to the re-export hub in rules/vanilla)."""
import pathlib
import re

ROOT = pathlib.Path("minecraft/rules/vanilla")
# matches: from ...constants import *  /  from ....helpers import RuleHelper  /  from ....data import X
DEEP = re.compile(r"^from \.{3,4}(constants|helpers|data) import .*$")

changed = 0
for path in sorted(ROOT.rglob("*.py")):
    if path.name == "__init__.py":
        continue
    lines = path.read_text(encoding="utf-8").splitlines()
    out, first_idx, removed = [], None, 0
    for i, line in enumerate(lines):
        if DEEP.match(line):
            if first_idx is None:
                first_idx = len(out)
            removed += 1
            continue
        out.append(line)
    if removed:
        out.insert(first_idx, "from .. import *  # constants, RuleHelper, mob sets (re-export hub)")
        path.write_text("\n".join(out) + "\n", encoding="utf-8")
        changed += 1

print(f"rewrote {changed} files")
