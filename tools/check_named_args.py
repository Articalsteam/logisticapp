#!/usr/bin/env python3
"""Structural checker: named arguments match the function they are passed to.

Fourth pass, and the one that covers the most ground: the UI is written almost
entirely with named arguments (`SectionCard(title = ..., subtitle = ...) { ... }`),
and a single misspelled parameter name is a compile error in every one of those call
sites. There is no compiler here, so this stands in for the part of one that knows
which parameters a function has.

Only *our* functions are checked. Calls into androidx / kotlinx / dev.gitlive are
ignored: their signatures are not in this source tree, and guessing at them would
produce noise that hides real problems.

Actions that are deliberately not checked (they need real type information):

  * whether a named argument exists on *some* overload of that name — names are
    pooled per function name, so an argument valid on one overload passes;
  * positional arguments;
  * whether a lambda's parameters are named correctly.

Usage:
  python3 check_named_args.py <root>
"""
import re
import sys
from pathlib import Path
from importlib.util import module_from_spec, spec_from_file_location

_spec = spec_from_file_location("chk", str(Path(__file__).with_name("check_dto_args.py")))
chk = module_from_spec(_spec)
_spec.loader.exec_module(chk)
mask_comments = chk.mask_comments

FUN_DECLARATION = re.compile(r'\bfun\s*(?:<[^>]*>\s*)?(?:[A-Za-z_][A-Za-z0-9_.<>?]*\s*\.\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(')
CALL = re.compile(r'(?<![\w.])([A-Za-z_][A-Za-z0-9_]*)\s*\(')
NAMED_ARG = re.compile(r'^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)')

# Parameters a lambda may be given that are not in the declaration's own list.
LAMBDA_HINT = '->'


def match_forward(text, index, opener, closer):
    depth = 0
    while index < len(text):
        if text[index] == opener:
            depth += 1
        elif text[index] == closer:
            depth -= 1
            if depth == 0:
                return index
        index += 1
    return len(text) - 1


def split_top_level(text):
    """Split on commas that are not inside brackets, braces or a lambda arrow."""
    parts = []
    depth = 0
    current = []
    for ch in text:
        if ch in '([{<':
            depth += 1
        elif ch in ')]}>':
            depth -= 1
        if ch == ',' and depth <= 0:
            parts.append(''.join(current))
            current = []
        else:
            current.append(ch)
    if ''.join(current).strip():
        parts.append(''.join(current))
    return parts


def parameter_names(header):
    """The parameter names in a function's header text."""
    names = set()
    for part in split_top_level(header):
        chunk = part.strip()
        if not chunk:
            continue
        # A lambda type's own parameters (`(String) -> Unit`) are not parameters of
        # the function; take the text before the first top-level '->'.
        arrow = chunk.find(LAMBDA_HINT)
        if arrow != -1:
            before, after = chunk[:arrow], chunk[arrow + 2:]
            # `onSelect: (T) -> Unit` — the name is still before the colon.
            if ':' in before:
                chunk = before + (after if ':' not in after else '')
            else:
                chunk = before
        chunk = re.sub(r'^(?:vararg|noinline|crossinline|val|var)\s+', '', chunk.strip())
        # Drop annotation arguments: `@Composable () -> Unit` and friends.
        chunk = re.sub(r'@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?', '', chunk).strip()
        name = re.match(r'([A-Za-z_][A-Za-z0-9_]*)', chunk)
        if name:
            names.add(name.group(1))
    return names


def index_functions(files):
    """function name -> set of parameter names, pooled across every declaration."""
    params = {}
    for path in files:
        masked = mask_comments(path.read_text())
        for match in FUN_DECLARATION.finditer(masked):
            name = match.group(1)
            close = match_forward(masked, match.end() - 1, '(', ')')
            header = masked[match.end():close]
            params.setdefault(name, set()).update(parameter_names(header))
    return params


def named_arguments(masked, open_index):
    """Named arguments passed in the call whose '(' is at [open_index]."""
    close = match_forward(masked, open_index, '(', ')')
    body = masked[open_index + 1:close]
    # Everything up to a trailing lambda belongs to the call's own argument list;
    # a lambda body may contain `x = y` assignments of its own.
    brace = body.find('{')
    if brace != -1:
        body = body[:brace]
    found = []
    for part in split_top_level(body):
        match = NAMED_ARG.match(part)
        if match:
            found.append(match.group(1))
    return found


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    root = Path(sys.argv[1])
    files = sorted(root.rglob("*.kt"))
    params = index_functions(files)

    problems = []
    checked = 0
    for path in files:
        masked = mask_comments(path.read_text())
        for match in CALL.finditer(masked):
            name = match.group(1)
            if name not in params:
                continue
            # A declaration, not a call.
            prefix = masked[max(0, match.start() - 12):match.start()]
            if re.search(r'fun\s+$', prefix) or re.search(r'fun\s*(?:<[^>]*>\s*)?$', prefix):
                continue
            for argument in named_arguments(masked, match.end() - 1):
                checked += 1
                if argument not in params[name]:
                    line = masked[:match.start()].count("\n") + 1
                    problems.append(
                        f"{path}:{line}: {name}(… {argument} = …) — "
                        f"{name} has no parameter named {argument}"
                    )

    for problem in problems:
        print(problem)
    print(f"\nchecked {checked} named arguments across {len(files)} files; {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
