"""Check every mixin target against each Minecraft version the mod builds for.

javac proves the mod's own code compiles against a version; it proves nothing about mixins. A mixin
names its target method as a string (``method = "extractRenderState(...)V"``), and so do its @At
targets, @Shadow members, @Accessor and @Invoker. When one of those is renamed or changes signature
the build is green and the game dies at startup. This reads the Stonecutter-processed sources for
each version (versions/<v>/build/generated/stonecutter/, so version conditions are already applied)
and resolves every such string against that version's actual Minecraft jar.

Mixins whose target is another mod's class (the advancement-screen compat adapters) are listed as
skipped: that mod's jar, not Minecraft's, decides them.

Run after building (so the processed sources exist):
    ./gradlew :26.1.2:compileClientJava :26.2:compileClientJava :26.3:compileClientJava
    python tools/check_mixin_targets.py            # every version in settings.gradle
    python tools/check_mixin_targets.py 26.3       # one version
Exits 1 when anything is missing.
"""
import os
import re
import struct
import sys
import zipfile

MOD = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "archipelago-euclesia-minecraft")
LOOM = os.path.expanduser("~/.gradle/caches/fabric-loom")


# -- class files --------------------------------------------------------------------------------

class ClassInfo:
    def __init__(self, data: bytes):
        pool = [None]
        count = struct.unpack(">H", data[8:10])[0]
        pos = 10
        index = 1
        while index < count:
            tag = data[pos]
            if tag == 1:
                length = struct.unpack(">H", data[pos + 1:pos + 3])[0]
                pool.append(data[pos + 3:pos + 3 + length].decode("utf-8", "replace"))
                pos += 3 + length
            elif tag in (3, 4):
                pool.append(None); pos += 5
            elif tag in (5, 6):
                pool.extend([None, None]); pos += 9; index += 1
            elif tag == 7:
                pool.append(("class", struct.unpack(">H", data[pos + 1:pos + 3])[0])); pos += 3
            elif tag in (8, 16, 19, 20):
                pool.append(None); pos += 3
            elif tag in (9, 10, 11, 12):   # field/method/interface-method ref, name-and-type
                pool.append((tag,) + struct.unpack(">HH", data[pos + 1:pos + 5])); pos += 5
            elif tag in (17, 18):
                pool.append(None); pos += 5
            elif tag == 15:
                pool.append(None); pos += 4
            else:
                raise ValueError(f"constant pool tag {tag}")
            index += 1

        def class_name(i):
            return pool[pool[i][1]] if i else None

        pos += 2  # access
        this, sup = struct.unpack(">HH", data[pos:pos + 4]); pos += 4
        self.name = class_name(this)
        self.super = class_name(sup)
        n = struct.unpack(">H", data[pos:pos + 2])[0]; pos += 2
        self.interfaces = [class_name(struct.unpack(">H", data[pos + 2 * k:pos + 2 * k + 2])[0]) for k in range(n)]
        pos += 2 * n
        self.fields, pos = self._members(data, pos, pool)
        self.methods, pos = self._members(data, pos, pool)
        # Every (owner, name, desc) this class's code touches: an @At target must be one of them, or
        # Mixin scans 0 targets and dies (26.2 kept TabNavigationBar but stopped calling it).
        self.refs = set()
        for entry in pool:
            if isinstance(entry, tuple) and entry[0] in (9, 10, 11):
                _tag, cls, nat = entry
                _nt, name, desc = pool[nat]
                self.refs.add((class_name(cls), pool[name], pool[desc]))

    @staticmethod
    def _members(data, pos, pool):
        out = set()
        n = struct.unpack(">H", data[pos:pos + 2])[0]; pos += 2
        for _ in range(n):
            _access, name, desc, attrs = struct.unpack(">HHHH", data[pos:pos + 8]); pos += 8
            out.add((pool[name], pool[desc]))
            for _ in range(attrs):
                length = struct.unpack(">I", data[pos + 2:pos + 6])[0]
                pos += 6 + length
        return out, pos


class Jar:
    def __init__(self, version):
        self.zips = [zipfile.ZipFile(os.path.join(LOOM, version, name))
                     for name in ("minecraft-client.jar", "minecraft-common.jar")
                     if os.path.exists(os.path.join(LOOM, version, name))]
        self.cache = {}

    def get(self, internal):
        if internal not in self.cache:
            info = None
            for z in self.zips:
                try:
                    info = ClassInfo(z.read(internal + ".class"))
                    break
                except KeyError:
                    continue
            self.cache[internal] = info
        return self.cache[internal]

    def hierarchy(self, internal):
        seen, todo = [], [internal]
        while todo:
            name = todo.pop()
            info = self.get(name) if name else None
            if info is None or name in seen:
                continue
            seen.append(name)
            todo += [info.super] + info.interfaces
        return [self.get(n) for n in seen]


# -- mixin sources ------------------------------------------------------------------------------

STRING = r'"((?:[^"\\]|\\.)*)"'


def strings_of(value: str) -> list:
    return re.findall(STRING, value)


def annotation_args(text: str, start: int) -> tuple:
    """The text inside the parentheses that open at ``start``, balanced."""
    depth, i = 0, start
    while i < len(text):
        c = text[i]
        if c == '"':
            i = text.index('"', i + 1)
            while text[i - 1] == "\\":
                i = text.index('"', i + 1)
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:i], i
        i += 1
    raise ValueError("unbalanced annotation")


def to_internal(fqn: str) -> str:
    """a.b.Outer.Inner -> a/b/Outer$Inner (a segment starting upper-case after the first is nested)."""
    parts = fqn.split(".")
    for k, part in enumerate(parts):
        if part[:1].isupper():
            return "/".join(parts[:k]) + "/" + "$".join(parts[k:])
    return "/".join(parts)


def strip_comments(text: str) -> str:
    """Drop comments, so inactive Stonecutter branches and prose don't count."""
    code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", code)


def collect_constants(roots) -> dict:
    """Every ``static final String NAME = "..." + OTHER + "...";`` in the sources, evaluated. Mixins take
    version-dependent descriptors from constants (client/utils/MixinTargets.java), which javac inlines
    into the annotation; this lets the checker see the same value."""
    raw = {}
    for root in roots:
        for dirpath, _dirs, files in os.walk(root):
            for f in files:
                if f.endswith(".java"):
                    code = strip_comments(open(os.path.join(dirpath, f), encoding="utf-8").read())
                    for name, expr, _last in re.findall(r"static\s+final\s+String\s+(\w+)\s*=\s*((?:" + STRING + r"|[^;\"])+);", code):
                        raw[name] = expr
    values = {}

    def value(name):
        if name not in values:
            parts = re.findall(STRING + r"|(\w+)", raw[name])
            values[name] = "".join(lit if lit or not ident else value(ident) for lit, ident in parts)
        return values[name]

    for name in raw:
        try:
            value(name)
        except (KeyError, RecursionError):
            pass
    return values


def inline_constants(code: str, constants: dict) -> str:
    """Replace ``MixinTargets.NAME`` / ``NAME`` with the literal it stands for."""
    def sub(m):
        name = m.group(2)
        return '"' + constants[name] + '"' if name in constants else m.group(0)
    return re.sub(r"\b(\w+\.)?([A-Z][A-Z0-9_]+)\b", sub, code)


def parse_mixin(path: str, constants: dict) -> dict | None:
    code = strip_comments(open(path, encoding="utf-8").read())
    m = re.search(r"@Mixin\s*\(", code)
    if not m:
        return None
    imports = {name.split(".")[-1]: name for name in re.findall(r"import\s+([\w.]+)\s*;", code)}
    package = re.search(r"package\s+([\w.]+)\s*;", code).group(1)
    args, _ = annotation_args(code, m.end() - 1)
    targets = []
    for simple in re.findall(r"([\w.]+)\.class", args):
        head, _, rest = simple.partition(".")
        if head[:1].islower():   # already fully qualified: @Mixin(net.minecraft....Foo.class)
            fqn = simple
        else:
            fqn = imports.get(head, f"{package}.{head}") + (f".{rest}" if rest else "")
        targets.append(to_internal(fqn))
    targets += [t.replace(".", "/") for t in re.findall(r'targets\s*=\s*\{?\s*' + STRING, args)]

    code = inline_constants(code, constants)
    # "a" + "b" -> "ab": a descriptor split over lines otherwise reads as its first piece alone.
    code = re.sub(r'"\s*\+\s*"', "", code)
    checks = []   # (kind, spec, line)
    for ann in re.finditer(r"@(Inject|Redirect|ModifyArg|ModifyArgs|ModifyVariable|ModifyConstant|ModifyReturnValue|ModifyExpressionValue|ModifyReceiver|WrapOperation|WrapWithCondition|WrapMethod|Overwrite)\s*\(", code):
        body, _ = annotation_args(code, ann.end() - 1)
        line = code.count("\n", 0, ann.start()) + 1
        mm = re.search(r"method\s*=\s*(\{[^}]*\}|" + STRING + ")", body)
        if mm:
            for spec in strings_of(mm.group(1)):
                checks.append(("method", spec, line))
        for tm in re.finditer(r"target\s*=\s*" + STRING, body):
            checks.append(("at", tm.group(1), line))
        # Still a name after inlining: a constant the checker could not evaluate. Counted as missing,
        # because silently skipping it is how a broken target would get an OK.
        for bad in re.finditer(r"(method|target)\s*=\s*([A-Za-z_][\w.]*)", body):
            checks.append(("unresolved", bad.group(2), line))
    for ann in re.finditer(r"@(Accessor|Invoker)\s*(\(\s*(?:value\s*=\s*)?" + STRING + r"\s*\))?[^;{]*?\s(\w+)\s*\(", code):
        kind, explicit, member = ann.group(1), ann.group(3), ann.group(4)
        if not explicit:
            explicit = re.sub(r"^(get|set|is|call|invoke)", "", member)
            explicit = explicit[:1].lower() + explicit[1:]
        checks.append((kind.lower(), explicit, code.count("\n", 0, ann.start()) + 1))
    for sh in re.finditer(r"@Shadow[^;{]*?\s(\w+)\s*(\(|;|=)", code):
        checks.append(("shadow", sh.group(1), code.count("\n", 0, sh.start()) + 1))
    return {"targets": targets, "checks": checks}


# -- resolution ---------------------------------------------------------------------------------

def check(jar: Jar, target: str, kind: str, spec: str):
    if kind == "unresolved":
        return False   # a constant the checker could not evaluate: nothing was verified
    info = jar.get(target)
    if kind == "method":
        name, _, desc = spec.partition("(")
        desc = "(" + desc if desc else None
        pool = info.methods
        return any(n == name and (desc is None or d == desc) for n, d in pool)
    if kind == "at":
        if not spec.startswith("L") or ";" not in spec:
            return True   # a bare name / constant target — nothing to resolve
        owner, _, member = spec[1:].partition(";")
        if not member:      # a class alone: the NEW of a constructor call
            return jar.get(owner) is not None
        if ":" in member:   # field: name:desc
            name, desc = member.split(":", 1)
            exists = any((name, desc) in c.fields for c in jar.hierarchy(owner))
        else:
            name, _, desc = member.partition("(")
            desc = "(" + desc
            exists = any((name, desc) in c.methods for c in jar.hierarchy(owner))
        # ponytail: class-wide, not per-method — a call that moved to a sibling method still passes.
        return exists and (owner, name, desc) in info.refs
    if kind == "accessor":
        return any(spec == n for c in jar.hierarchy(target) for n, _ in c.fields)
    if kind in ("invoker", "shadow"):
        return any(spec == n for c in jar.hierarchy(target) for n, _ in c.methods | c.fields)
    return True


def versions() -> list:
    text = open(os.path.join(MOD, "settings.gradle"), encoding="utf-8").read()
    return re.findall(r"'(\d+\.\d+(?:\.\d+)?)'", re.search(r"versions\s+([^\n]+)", text).group(1))


def active_version() -> str:
    text = open(os.path.join(MOD, "stonecutter.gradle"), encoding="utf-8").read()
    return re.search(r"stonecutter\.active\s*\(?\s*[\"']([^\"']+)", text).group(1)


def main() -> int:
    wanted = sys.argv[1:] or versions()
    failures = 0
    for version in wanted:
        jar = Jar(version)
        if version == active_version():
            # Stonecutter compiles the active version straight from src/; its generated tree is stale.
            roots = [os.path.join(MOD, "src", side, "java") for side in ("main", "client")]
        else:
            roots = [os.path.join(MOD, "versions", version, "build", "generated", "stonecutter", side, "java")
                     for side in ("main", "client")]
        if not all(os.path.isdir(r) for r in roots):
            print(f"{version}: no processed sources — build :{version}:compileClientJava first")
            failures += 1
            continue
        constants = collect_constants(roots)
        checked = skipped = 0
        missing = []
        for root in roots:
            for dirpath, _dirs, files in os.walk(root):
                for f in files:
                    if not f.endswith(".java"):
                        continue
                    path = os.path.join(dirpath, f)
                    parsed = parse_mixin(path, constants)
                    if not parsed:
                        continue
                    rel = os.path.relpath(path, root).replace(os.sep, "/")
                    for target in parsed["targets"]:
                        if jar.get(target) is None:
                            skipped += 1   # not a Minecraft class: a compat target from another mod
                            continue
                        for kind, spec, line in parsed["checks"]:
                            checked += 1
                            if not check(jar, target, kind, spec):
                                missing.append(f"  {rel}:{line}  {kind} {spec!r} not in {target}")
        status = "OK" if not missing else f"{len(missing)} MISSING"
        print(f"{version}: {checked} mixin references checked, {skipped} external targets skipped — {status}")
        for line in missing:
            print(line)
        failures += len(missing)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
