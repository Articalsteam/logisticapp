#!/usr/bin/env python3
"""Finds function parameters that the body never mentions.

Why this exists: a parameter that is accepted and silently ignored is how a screen
ends up pretending to do something. `CompanyLogo(name, logoUrl = company.logoUrl)`
that draws an initial and never loads the URL reads as "the uploaded logo is shown"
to anyone at the call site. The Kotlin compiler does not warn about this, and the
other checkers in this directory do not look for it.

How it works, per `fun` with a body:

  * the parameter list is read from the balanced parentheses;
  * the body is either the brace-matched block, or, for an expression body, the
    text from `=` to the next declaration at the same or lower indentation;
  * every parameter name is looked for as a whole word in the body plus the
    *other* parameters' default expressions (so `fun f(a: Int, b: Int = a)`
    counts `a` as used).

`override` declarations are skipped: an unused parameter there is the interface's
shape, not a mistake. `expect`/abstract declarations have no body and are skipped.

Deliberately conservative — a clean run is not a proof:

  * comments are *not* stripped, so a parameter mentioned only in a comment or KDoc
    counts as used (this can only hide a problem, never invent one);
  * a parameter that is mentioned but ignored in practice is not detected;
  * types, overloads and whether the value is the right one are all out of scope;
  * if the next declaration cannot be located, the body window extends further than
    the real body, which again can only hide a problem.

Exit code is 0 when nothing is found, 1 otherwise, so CI can gate on it.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

FUN_RE = re.compile(r"\bfun\b[ \t]+(?P<name>[A-Za-z_][A-Za-z0-9_]*)")

# A line that could start the next declaration. Used only to stop a body window.
DECL_RE = re.compile(
    r"^[ \t]*(?:@|fun\b|val\b|var\b|const\b|class\b|object\b|interface\b|enum\b|"
    r"sealed\b|data\b|value\b|annotation\b|typealias\b|companion\b|init\b|"
    r"typealias\b|package\b|import\b|public\b|private\b|internal\b|protected\b|"
    r"override\b|expect\b|actual\b|suspend\b|operator\b|infix\b|tailrec\b|"
    r"external\b|inline\b|noinline\b|crossinline\b|abstract\b|open\b|final\b)"
)

# A declaration may state, in writing, which parameters it deliberately ignores:
#   // unused-params-allow: subject — the iOS share sheet has no subject
# Exemptions are reported on every run so they cannot be quietly forgotten.
ALLOW_RE = re.compile(r"unused-params-allow:[ \t]*([A-Za-z0-9_, \t]+)")

# Lines that are only modifiers/annotations, i.e. they may precede `fun`.
MODIFIER_RE = re.compile(
    r"^[ \t]*(?:@[\w.]+(?:\(.*\))?|public|private|internal|protected|override|"
    r"suspend|inline|expect|actual|operator|infix|tailrec|external|abstract|open|"
    r"final|const|reified)[ \t]*$"
)


def indentation(text: str, index: int) -> int:
    """Column of the first non-space character on the line containing `index`."""
    line_start = text.rfind("\n", 0, index) + 1
    i = line_start
    while i < len(text) and text[i] in " \t":
        i += 1
    return i - line_start


def match_balanced(text: str, start: int, open_ch: str, close_ch: str) -> int | None:
    depth = 0
    i = start
    while i < len(text):
        ch = text[i]
        if ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


def split_top_level(params: str) -> list[str]:
    """Splits on top-level commas.

    Angle brackets are deliberately *not* counted: the `>` in `->` and a `>` in a
    comparison would unbalance the depth, and a generic comma only ever yields a
    fragment without a `name: Type` head, which `param_parts` discards.
    """
    out: list[str] = []
    depth = 0
    current: list[str] = []
    for ch in params:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(current))
            current = []
        else:
            current.append(ch)
    if current:
        out.append("".join(current))
    return [p.strip() for p in out if p.strip()]


def param_parts(param: str) -> tuple[str | None, str]:
    """Returns (name, default expression) — name is None when there is no `name: Type`."""
    names_and_default = param.split("=", 1)
    head = names_and_default[0]
    default = names_and_default[1] if len(names_and_default) > 1 else ""
    if ":" not in head:
        return None, default
    name = head.split(":", 1)[0].strip()
    name = re.sub(r"^(?:vararg|noinline|crossinline)[ \t]+", "", name).strip()
    return (name if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name) else None), default


def declared_exemptions(text: str, fun_start: int) -> set[str]:
    """Parameters the declaration says, in a comment, that it ignores on purpose."""
    line_start = text.rfind("\n", 0, fun_start) + 1
    lines: list[str] = [text[line_start:text.find("\n", line_start) if text.find("\n", line_start) != -1 else len(text)]]
    cursor = line_start
    while cursor > 0:
        prev_end = cursor - 1
        prev_start = text.rfind("\n", 0, prev_end) + 1
        prev_line = text[prev_start:prev_end]
        if not prev_line.strip():
            break
        if not (prev_line.lstrip().startswith(("//", "*", "/*", "@")) or MODIFIER_RE.match(prev_line)):
            break
        lines.append(prev_line)
        cursor = prev_start
    found: set[str] = set()
    for match in ALLOW_RE.finditer("\n".join(lines)):
        found.update(name.strip() for name in match.group(1).split(",") if name.strip())
    return found


def declaration_prefix(text: str, fun_start: int) -> str:
    """Text of the declaration line before `fun`, plus preceding annotation/modifier
    lines — enough to see an `override`."""
    line_start = text.rfind("\n", 0, fun_start) + 1
    prefix_lines = [text[line_start:fun_start]]
    cursor = line_start
    while cursor > 0:
        prev_end = cursor - 1
        prev_start = text.rfind("\n", 0, prev_end) + 1
        prev_line = text[prev_start:prev_end]
        if MODIFIER_RE.match(prev_line):
            prefix_lines.append(prev_line)
            cursor = prev_start
        else:
            break
    return "\n".join(prefix_lines)


def body_window_end(text: str, from_index: int, fun_indent: int) -> int:
    """Index where the declaration ends: the first later line that is non-blank, is
    indented no deeper than `fun`, and looks like a new declaration."""
    i = text.find("\n", from_index)
    if i == -1:
        return len(text)
    while True:
        line_start = i + 1
        if line_start >= len(text):
            return len(text)
        line_end = text.find("\n", line_start)
        if line_end == -1:
            line_end = len(text)
        line = text[line_start:line_end]
        if line.strip() and indentation(text, line_start) <= fun_indent and DECL_RE.match(line):
            return line_start
        i = line_end
        if i >= len(text):
            return len(text)


def scan(path: Path) -> tuple[list[str], list[str]]:
    text = path.read_text()
    problems: list[str] = []
    exemptions: list[str] = []
    for m in FUN_RE.finditer(text):
        name = m.group("name")
        if name == "interface":  # `fun interface`
            continue
        if re.search(r"\boverride\b", declaration_prefix(text, m.start())):
            continue
        indent = indentation(text, m.start())
        exempt = declared_exemptions(text, m.start())
        paren = text.find("(", m.end())
        if paren == -1:
            continue
        close = match_balanced(text, paren, "(", ")")
        if close is None:
            continue
        params = split_top_level(text[paren + 1:close])
        names: list[tuple[str, str]] = []
        for p in params:
            pname, default = param_parts(p)
            if pname and pname != "_":
                names.append((pname, default))
        if not names:
            continue

        window_end = body_window_end(text, close + 1, indent)
        window = text[close + 1:window_end]
        brace = window.find("{")
        eq = window.find("=")
        if brace != -1 and (eq == -1 or brace < eq):
            body_end = match_balanced(text, close + 1 + brace, "{", "}")
            if body_end is None:
                continue
            usage = text[close + 1 + brace:body_end + 1]
        elif eq != -1:
            usage = window[eq:]
        else:
            continue  # abstract / expect declaration: nothing to check
        usage += "\n" + "\n".join(default for _, default in names if default)

        line = text.count("\n", 0, m.start()) + 1
        for pname, _ in names:
            if pname in exempt:
                exemptions.append(f"{path}:{line}: fun {name} — '{pname}' exempted in source")
            if not re.search(r"\b" + re.escape(pname) + r"\b", usage) and pname not in exempt:
                problems.append(f"{path}:{line}: fun {name} — parameter '{pname}' is never used")
    return problems, exemptions


def main() -> int:
    roots = sys.argv[1:] or ["composeApp/src"]
    files = sorted(p for root in roots for p in Path(root).rglob("*.kt"))
    all_problems: list[str] = []
    all_exemptions: list[str] = []
    for f in files:
        problems, exemptions = scan(f)
        all_problems.extend(problems)
        all_exemptions.extend(exemptions)
    print(f"\nchecked {len(files)} files; {len(all_problems)} unused parameter(s)")
    for p in all_problems:
        print(" ", p)
    if all_exemptions:
        print(f"{len(all_exemptions)} declared exemption(s), listed so they stay visible:")
        for e in all_exemptions:
            print(" ", e)
    return 1 if all_problems else 0


if __name__ == "__main__":
    sys.exit(main())
