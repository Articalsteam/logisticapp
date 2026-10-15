#!/usr/bin/env python3
"""Structural checker: every Kotlin file is closed, with comments and literals removed.

The other four tools parse the tree with regular expressions and do not care whether a
brace is missing. This one does. It walks each file with a small lexer so that the
things that look like code and are not — comments, `//` in a URL, an apostrophe inside a
backticked test name, a `{tenantId}` route pattern inside a string, a `${...}` template
that contains a lambda — are removed before anything is counted.

Usage:
  python3 check_balance.py <root>

Exit code is non-zero when a file is unbalanced, so it can be chained.
"""

from __future__ import annotations

import sys
from pathlib import Path

OPENERS = {"{": "}", "(": ")", "[": "]"}


def strip(text: str) -> str:
    """Return the text with comments and literals replaced by spaces.

    Offsets are preserved (replaced characters become spaces) so reported line numbers
    still point at the real file. String templates keep their code: `${order.id}` is
    expressions, and expressions can contain braces, lambdas and further strings.
    """
    out: list[str] = []
    i, n = 0, len(text)
    # Each frame is "code" (top level or a template) or "string"; a template starts in
    # code at depth 1 and returns to the string that opened it when the brace closes.
    state: list[str] = ["code"]
    depth: list[int] = [0]  # brace depth inside the current template frame

    def emit(characters: str) -> None:
        out.append(characters)

    def blank(count: int) -> None:
        out.append(" " * count)

    while i < n:
        c = text[i]
        top = state[-1]

        if top == "code":
            if c == "/" and i + 1 < n and text[i + 1] == "/":
                while i < n and text[i] != "\n":
                    blank(1)
                    i += 1
                continue
            if c == "/" and i + 1 < n and text[i + 1] == "*":
                depth_before = i
                levels = 1
                i += 2
                while i < n and levels:
                    if text.startswith("/*", i):
                        levels += 1
                        i += 2
                    elif text.startswith("*/", i):
                        levels -= 1
                        i += 2
                    else:
                        i += 1
                emit("".join("\n" if ch == "\n" else " " for ch in text[depth_before:i]))
                continue
            if c == "`":
                j = i + 1
                while j < n and text[j] != "`":
                    j += 1
                blank(min(j + 1, n) - i)
                i = min(j + 1, n)
                continue
            if c == '"':
                emit(" ")
                state.append("string")
                i += 1
                continue
            if c == "'":
                j = i + 1
                while j < n and text[j] != "'" and text[j] != "\n":
                    j += 1
                blank(min(j + 1, n) - i)
                i = min(j + 1, n)
                continue
            if c == "{" and len(state) > 1:
                depth[-1] += 1
            elif c == "}" and len(state) > 1:
                depth[-1] -= 1
                emit(c)
                i += 1
                if depth[-1] == 0:
                    state.pop()
                    depth.pop()
                continue
            emit(c)
            i += 1
            continue

        # inside a string literal
        if c == "\\":
            blank(2)
            i += 2
            continue
        if c == '"':
            emit(" ")
            state.pop()
            i += 1
            continue
        if c == "\n":
            # Not valid Kotlin (an unterminated literal), but do not run away.
            emit("\n")
            i += 1
            continue
        if c == "$" and i + 1 < n and text[i + 1] == "{":
            # Count the template's own braces so they stay balanced.
            emit("{")
            i += 2
            state.append("code")
            depth.append(1)
            continue
        if c == "$":
            # `$name` — a plain interpolation, nothing to parse.
            blank(1)
            i += 1
            continue
        blank(1)
        i += 1

    return "".join(out)


def check(path: Path) -> list[str]:
    code = strip(path.read_text(encoding="utf-8"))
    stack: list[tuple[str, int, int]] = []
    problems: list[str] = []
    line, column = 1, 1
    for ch in code:
        if ch == "\n":
            line, column = line + 1, 1
            continue
        if ch in OPENERS:
            stack.append((ch, line, column))
        elif ch in "})]":
            if not stack:
                problems.append(f"{path}:{line}:{column}: closing '{ch}' with nothing open")
            else:
                opener, oline, ocolumn = stack.pop()
                if OPENERS[opener] != ch:
                    problems.append(
                        f"{path}:{line}:{column}: '{ch}' closes '{opener}' opened at "
                        f"{oline}:{ocolumn}",
                    )
        column += 1
    for opener, oline, ocolumn in stack:
        problems.append(f"{path}:{oline}:{ocolumn}: '{opener}' is never closed")
    return problems


def main(argv: list[str]) -> int:
    root = Path(argv[1] if len(argv) > 1 else ".")
    files = sorted(root.rglob("*.kt"))
    problems: list[str] = []
    for path in files:
        problems.extend(check(path))
    for problem in problems:
        print(problem)
    print(f"\nchecked {len(files)} files; {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
