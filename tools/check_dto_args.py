#!/usr/bin/env python3
"""Structural checker: named-argument usage of data classes in a Kotlin source tree.

Written because the Kotlin in this repository cannot be compiled in the sandbox
(no kotlinc, no Android SDK). It catches the most common authoring mistake in
never-compiled code: a named argument that the target data class does not have.

Usage: python3 check_dto_args.py <root> [paths...]
"""
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------- parsing


def mask_comments(text: str) -> str:
    """Replace comment and string contents with spaces, keeping offsets and newlines."""
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == '/' and i + 1 < n and text[i + 1] == '/':
            while i < n and text[i] != '\n':
                out[i] = ' '
                i += 1
        elif ch == '/' and i + 1 < n and text[i + 1] == '*':
            depth = 0
            while i < n:
                if text[i] == '/' and i + 1 < n and text[i + 1] == '*':
                    depth += 1
                    out[i] = out[i + 1] = ' '
                    i += 2
                    continue
                if text[i] == '*' and i + 1 < n and text[i + 1] == '/':
                    depth -= 1
                    out[i] = out[i + 1] = ' '
                    i += 2
                    if depth == 0:
                        break
                    continue
                if text[i] != '\n':
                    out[i] = ' '
                i += 1
        elif ch == '"':
            if text[i:i + 3] == '"""':
                out[i] = out[i + 1] = out[i + 2] = ' '
                i += 3
                while i < n and not text.startswith('"""', i):
                    if text[i] != '\n':
                        out[i] = ' '
                    i += 1
                i += 3
            else:
                out[i] = ' '
                i += 1
                while i < n and text[i] != '"':
                    if text[i] == '\\':
                        out[i] = ' '
                        i += 1
                    if i < n and text[i] != '\n':
                        out[i] = ' '
                    i += 1
                if i < n:
                    out[i] = ' '
                i += 1
        else:
            i += 1
    return ''.join(out)


CLASS_RE = re.compile(r'\b(?:data\s+)?class\s+([A-Z][A-Za-z0-9_]*)\s*(?:<[^>]*>)?\s*\(')


def class_props(masked: str) -> dict:
    """Map class name -> set of primary-constructor parameter names."""
    result = {}
    for m in CLASS_RE.finditer(masked):
        name = m.group(1)
        start = m.end() - 1
        depth = 0
        i = start
        while i < len(masked):
            if masked[i] in '([{':
                depth += 1
            elif masked[i] in ')]}':
                depth -= 1
                if depth == 0:
                    break
            i += 1
        params = masked[start + 1:i]
        props = set()
        depth = 0
        token = ''
        parts = []
        for ch in params + ',':
            if ch in '([{<':
                depth += 1
            elif ch in ')]}>':
                depth -= 1
            if ch == ',' and depth == 0:
                parts.append(token)
                token = ''
            else:
                token += ch
        for part in parts:
            part = part.strip()
            if not part:
                continue
            head = part.split(':', 1)[0].strip()
            head = head.split('=', 1)[0].strip()
            head = re.sub(r'^(@[A-Za-z_][A-Za-z0-9_.]*(\([^)]*\))?\s*)+', '', head)
            head = re.sub(
                r'^(?:(?:private|internal|public|protected|override|final|open|lateinit|var|val|data|constructor)\s+)+',
                '', head,
            ).strip()
            if re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', head):
                props.add(head)
        result.setdefault(name, set()).update(props)
    return result


def find_call_args(masked: str, name: str):
    """Yield (position, args_text) for `Name(` calls (constructor-ish)."""
    for m in re.finditer(r'(?<![A-Za-z0-9_.])' + re.escape(name) + r'\s*\(', masked):
        start = m.end() - 1
        depth = 0
        i = start
        while i < len(masked):
            if masked[i] in '([{':
                depth += 1
            elif masked[i] in ')]}':
                depth -= 1
                if depth == 0:
                    break
            i += 1
        yield m.start(), masked[start + 1:i]


def split_top_level(text: str) -> list:
    parts = []
    depth = 0
    token = ''
    for ch in text:
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(token)
            token = ''
        else:
            token += ch
    parts.append(token)
    return [p.strip() for p in parts if p.strip()]


def line_of(text: str, offset: int) -> int:
    return text.count('\n', 0, offset) + 1


def main() -> int:
    root = Path(sys.argv[1])            # where declarations live (the whole tree)
    targets = [Path(p) for p in sys.argv[2:]] or [root]

    def collect(paths):
        files = []
        for t in paths:
            if t.is_dir():
                files.extend(sorted(t.rglob('*.kt')))
            else:
                files.append(t)
        return files

    sources = collect(targets)

    # Pass 1: every declaration in the tree, so a file can use a DTO declared elsewhere.
    props = {}
    for path in collect([root]):
        for name, keys in class_props(mask_comments(path.read_text())).items():
            props.setdefault(name, set()).update(keys)

    raw = {path: path.read_text() for path in sources}

    problems = 0
    checked = 0
    for path in sources:
        text = raw[path]
        masked = mask_comments(text)
        for name, keys in props.items():
            if name not in masked:
                continue
            # Skip the declaration site.
            for offset, args in find_call_args(masked, name):
                if re.search(r'class\s+$', masked[max(0, offset - 20):offset]):
                    continue
                for part in split_top_level(args):
                    m = re.match(r'^([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)', part)
                    if not m:
                        continue
                    arg = m.group(1)
                    checked += 1
                    if arg not in keys:
                        problems += 1
                        print(f"{path}:{line_of(text, offset)}: {name}({arg} = ...) — "
                              f"unknown parameter; {name} has: {', '.join(sorted(keys))}")
    print(f"\nchecked {checked} named arguments across {len(sources)} files; {problems} problem(s)")
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
