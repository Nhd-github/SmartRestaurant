#!/usr/bin/env python3
"""Lightweight offline structural validation for the project sources."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
ASL = ROOT / "src" / "asl"

FORBIDDEN = {
    "quoted manager receiver": r"'restaurantManager'",
    "old manager source": r"source\(manager\)",
    "old maxDailyDish action": r"(?m)^\s*maxDailyDish\(Max\);",
    "unsupported Canvas double buffering": r"getCanvas\(\)\.setDoubleBuffered",
    "invalid reversed less-or-equal operator": r"=<",
    "invalid reversed greater-or-equal operator": r"=>",
}


def strip_comments_and_strings(text: str) -> str:
    out = []
    i = 0
    in_string = False
    escaped = False
    while i < len(text):
        c = text[i]
        if in_string:
            out.append(" ")
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
            out.append(" ")
            i += 1
            continue
        if c == "/" and i + 1 < len(text) and text[i + 1] == "/":
            while i < len(text) and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def check_delimiters(path: Path) -> None:
    text = strip_comments_and_strings(path.read_text(encoding="utf-8"))
    pairs = {"(": ")", "[": "]", "{": "}"}
    stack = []
    for index, char in enumerate(text):
        if char in pairs:
            stack.append((char, index))
        elif char in pairs.values():
            if not stack or pairs[stack[-1][0]] != char:
                raise ValueError(f"{path.name}: unexpected {char} at offset {index}")
            stack.pop()
    if stack:
        raise ValueError(f"{path.name}: unclosed {stack[-1][0]} at offset {stack[-1][1]}")


def main() -> int:
    errors = []
    files = sorted(ASL.glob("*.asl"))
    if len(files) != 10:
        errors.append(f"Expected 10 ASL files, found {len(files)}")

    for path in files:
        try:
            check_delimiters(path)
        except ValueError as exc:
            errors.append(str(exc))

    source_paths = list(ASL.glob("*.asl")) + [
        ROOT / "RestaurantEnv.java",
        ROOT / "RestaurantModel.java",
        ROOT / "RestaurantView.java",
        ROOT / "smartRestaurant.mas2j",
    ]
    all_text = "\n".join(p.read_text(encoding="utf-8") for p in source_paths)
    for label, pattern in FORBIDDEN.items():
        if re.search(pattern, all_text):
            errors.append(f"Forbidden legacy pattern found: {label}")

    required = [
        "reserveRecipe", "reservationPreempted", "-!performCooking",
        "recordReplan", "setSatisfaction", "waiterReputation",
        "findAlternative", "Smart Restaurant MAS - Hard Coordination Demo",
        "pendingSupply", "dispatchSupplies", "cancelCustomerSupplies",
        "deliverSupply(Item, Table, Customer)", "getTableItemsSummary",
    ]
    for token in required:
        if token not in all_text:
            errors.append(f"Required feature token missing: {token}")

    if errors:
        print("VALIDATION FAILED")
        for error in errors:
            print(" -", error)
        return 1

    print("VALIDATION PASSED")
    print(f" - {len(files)} AgentSpeak files")
    print(" - balanced delimiters")
    print(" - legacy-error patterns absent")
    print(" - hard-coordination feature markers present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
