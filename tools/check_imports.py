#!/usr/bin/env python3
"""Structural checker: every `import com.articals.logistics.**` resolves.

Third pass after check_dto_args.py and check_member_calls.py, for the same reason: the
Kotlin here has never been through a compiler, and a wrong import is the single most
common way a large hand-written UI fails to build. This one builds an index of every
top-level (and nested, one level deep) declaration in the source tree and then checks
each import against it.

What it cannot see: whether the *type* of a name is right, whether a function's
arguments match, or whether an external (androidx / kotlinx / dev.gitlive) import
exists at all. It finds missing and misspelled names in our own code, which is the part
a compiler would otherwise have to tell us at the end of a long build.

Usage:
  python3 check_imports.py <root> [--allow <prefix> ...]

Exit code is non-zero when an import cannot be resolved.
"""
import re
import sys
from pathlib import Path

PACKAGE = re.compile(r'^package\s+([A-Za-z0-9_.]+)', re.M)
IMPORT = re.compile(r'^import\s+([A-Za-z0-9_.]+)', re.M)

# Declarations worth indexing. `fun` covers top-level functions such as
# `formatMinor`; `val`/`var` cover top-level properties.
DECLARATION = re.compile(
    r'^(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s*)*'
    r'(?:(?:public|internal|private|protected|expect|actual|inline|suspend|open|abstract|'
    r'sealed|data|enum|annotation|value|const|external|tailrec|operator|infix|override|'
    r'lateinit|companion)\s+)*'
    r'(?:class|interface|object|fun|val|var|typealias)\s*'
    r'(?:<[^>]*>\s*)?'
    r'([A-Za-z_][A-Za-z0-9_]*)',
    re.M,
)
NESTED_OBJECT = re.compile(r'^\s+(?:public\s+|internal\s+)?object\s+([A-Za-z_][A-Za-z0-9_]*)', re.M)


def index_sources(files):
    """Map fully-qualified name -> file, for every declaration we can see."""
    index = {}
    nested = {}
    for path in files:
        text = path.read_text()
        package = PACKAGE.search(text)
        if not package:
            continue
        pkg = package.group(1)
        body = text[package.end():]
        for match in DECLARATION.finditer(body):
            index.setdefault(f"{pkg}.{match.group(1)}", path)
            # Extension functions: `fun FirebaseFirestore.documentFlow(...)` is
            # imported as `documentFlow`, not as `FirebaseFirestore`.
            after = body[match.end():match.end() + 80]
            receiver_call = re.match(r'\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)', after)
            if receiver_call:
                index.setdefault(f"{pkg}.{receiver_call.group(1)}", path)

        # Nested objects (and classes) one level deep inside a top-level declaration:
        # `ArticalsStrings.Settings` is the member an import can name.
        for outer in DECLARATION.finditer(body):
            start = outer.end()
            brace = body.find('{', start)
            if brace == -1:
                continue
            depth = 0
            for i in range(brace, len(body)):
                if body[i] == '{':
                    depth += 1
                elif body[i] == '}':
                    depth -= 1
                    if depth == 0:
                        inner = body[brace + 1:i]
                        for nested_match in NESTED_OBJECT.finditer(inner):
                            name = f"{pkg}.{outer.group(1)}.{nested_match.group(1)}"
                            nested.setdefault(name, path)
                        break
    index.update(nested)
    return index


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    root = Path(sys.argv[1])
    allowed_prefixes = []
    args = sys.argv[2:]
    while args:
        flag = args.pop(0)
        if flag == "--allow" and args:
            allowed_prefixes.append(args.pop(0))

    files = sorted(root.rglob("*.kt"))
    index = index_sources(files)

    problems = []
    checked = 0
    for path in files:
        text = path.read_text()
        for match in IMPORT.finditer(text):
            target = match.group(1)
            if not target.startswith("com.articals.logistics"):
                continue
            if any(target.startswith(prefix) for prefix in allowed_prefixes):
                continue
            # Wildcard imports are checked by prefix instead of by name.
            if target.endswith(".*"):
                prefix = target[:-2] + "."
                if not any(name.startswith(prefix) for name in index):
                    problems.append(f"{path}: {target} — nothing in that package")
                checked += 1
                continue
            checked += 1
            if target not in index:
                line = text[:match.start()].count("\n") + 1
                problems.append(f"{path}:{line}: {target} — no such declaration")

    for problem in problems:
        print(problem)
    print(f"\nchecked {checked} imports across {len(files)} files; {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
