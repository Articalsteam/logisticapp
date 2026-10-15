#!/usr/bin/env python3
"""Structural checker: member access on known demo objects.

Second pass after check_dto_args.py, for the same reason: the Kotlin here has never
been through a compiler. This one answers "does `store.foo(...)` exist?", including
members inherited from interfaces and extension functions declared in the same
source tree.

Usage:
  python3 check_member_calls.py <root> store=DemoStore [controls=DemoControls ...]
"""
import re
import sys
from pathlib import Path

from importlib.util import module_from_spec, spec_from_file_location

_spec = spec_from_file_location("chk", str(Path(__file__).with_name("check_dto_args.py")))
chk = module_from_spec(_spec)
_spec.loader.exec_module(chk)
mask_comments = chk.mask_comments

DECL = re.compile(r'\b(?:class|interface)\s+([A-Z][A-Za-z0-9_]*)\b')
OBJECT_DECL = re.compile(r'\bobject\s+([A-Z][A-Za-z0-9_]*)\b')
IDENTIFIER_TYPES = re.compile(r'\b([A-Z][A-Za-z0-9_]*)\b')


def match_forward(text: str, index: int, opener: str, closer: str) -> int:
    """Index just past the matching closer, starting at `opener` (index points at it)."""
    depth = 0
    i = index
    while i < len(text):
        if text[i] == opener:
            depth += 1
        elif text[i] == closer:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(text) - 1


def brace_body(masked: str, open_index: int):
    depth = 0
    i = open_index
    while i < len(masked):
        if masked[i] == '{':
            depth += 1
        elif masked[i] == '}':
            depth -= 1
            if depth == 0:
                return masked[open_index + 1:i]
        i += 1
    return masked[open_index + 1:]


def class_declarations(masked: str):
    """Yield (name, supertype_text, body_text) for every class and interface."""
    for m in DECL.finditer(masked):
        name = m.group(1)
        i = m.end()
        # optional primary constructor
        paren = masked.find('(', i)
        brace = masked.find('{', i)
        end_of_header = brace
        if paren != -1 and (brace == -1 or paren < brace):
            close = match_forward(masked, paren, '(', ')')
            header = masked[paren:close]
            i = close + 1
        else:
            header = ''
            i = brace if brace != -1 else len(masked)
        brace = masked.find('{', i)
        if brace == -1:
            continue
        close = match_forward(masked, brace, '{', '}')
        supers = IDENTIFIER_TYPES.findall(masked[i:brace])
        yield name, set(supers), header, masked[brace + 1:close]


def collect(paths):
    files = []
    for t in paths:
        if t.is_dir():
            files.extend(sorted(t.rglob('*.kt')))
        else:
            files.append(t)
    return files


def main() -> int:
    root = Path(sys.argv[1])
    receivers = {}
    for spec in sys.argv[2:]:
        name, _, cls = spec.partition('=')
        receivers[name] = cls

    sources = collect([root])
    masked = {p: mask_comments(p.read_text()) for p in sources}
    text = {p: p.read_text() for p in sources}

    objects = {}      # object -> set(member names)
    members = {}      # class -> set(member names)
    supers = {}       # class -> set(interface names it implements)
    extensions = {}   # type name -> set(function names)

    for path, body in masked.items():
        for cls, supers_found, header, inner in class_declarations(body):
            own = set(re.findall(r'\b(?:fun|val|var)\s*(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)', inner))
            ctor = chk.class_props("class X" + header + " {").get("X", set())
            members.setdefault(cls, set()).update(own | ctor)
            supers.setdefault(cls, set()).update(supers_found)
        for m in OBJECT_DECL.finditer(body):
            brace = body.find('{', m.end())
            if brace == -1:
                continue
            inner = brace_body(body, brace)
            # Nested objects count as members: `ArticalsStrings.Shell` is a member
            # of `ArticalsStrings`, and a checker that disagreed would report
            # hundreds of false problems on every screen.
            own = set(
                re.findall(
                    r'\b(?:fun|val|var|const\s+val|object)\s*(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)',
                    inner,
                )
            )
            objects.setdefault(m.group(1), set()).update(own)
        for m in re.finditer(r'\bfun\s*(?:<[^>]*>\s*)?([A-Z][A-Za-z0-9_]*)\s*\.\s*([a-z][A-Za-z0-9_]*)', body):
            extensions.setdefault(m.group(1), set()).add(m.group(2))
        for m in re.finditer(r'\b(?:val|var)\s+([A-Z][A-Za-z0-9_]*)\s*\.\s*([a-z][A-Za-z0-9_]*)', body):
            extensions.setdefault(m.group(1), set()).add(m.group(2))

    def known(cls: str, seen=None) -> set:
        seen = seen or set()
        if cls in seen:
            return set()
        seen.add(cls)
        found = set(members.get(cls, set()))
        for sup in supers.get(cls, set()):
            found |= known(sup, seen)
        return found

    problems = 0
    checked = 0
    for receiver, cls in receivers.items():
        allowed = known(cls) | extensions.get(cls, set())
        pattern = re.compile(r'(?<![A-Za-z0-9_.])' + re.escape(receiver) + r'\.([a-z][A-Za-z0-9_]*)')
        for path, body in masked.items():
            if path.name.endswith('DemoStore.kt') and receiver == 'controls':
                continue
            for m in pattern.finditer(body):
                member = m.group(1)
                checked += 1
                if member not in allowed:
                    problems += 1
                    line = text[path].count('\n', 0, m.start()) + 1
                    print(f"{path}:{line}: {receiver}.{member} — not a member of {cls}")

    # Static access on declared objects and companions (OrderStatus.canTransition, DemoIds.orderNumber, ...).
    static_pattern = re.compile(r'(?<![A-Za-z0-9_.])([A-Z][A-Za-z0-9_]*)\.([a-zA-Z_][A-Za-z0-9_]*)')
    for path, body in masked.items():
        for m in static_pattern.finditer(body):
            holder, member = m.group(1), m.group(2)
            allowed = objects.get(holder)
            if allowed is None:
                continue          # a class, not an object: instances are checked above
            checked += 1
            if member not in allowed:
                problems += 1
                line = text[path].count('\n', 0, m.start()) + 1
                print(f"{path}:{line}: {holder}.{member} — not a member of object {holder}")

    print(f"\nchecked {checked} accesses; {problems} problem(s)")
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
