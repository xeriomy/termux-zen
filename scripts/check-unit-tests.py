#!/usr/bin/env python3
"""Summarise JUnit XML and fail on any unit-test failure.

The whole :app suite is green on JDK 17, so this gate has no quarantine:
every failure or error fails the job. The minimum test count guards against a
suite selection change that silently stops running the plugin tests.
"""

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("results", help="Gradle test-results directory containing TEST-*.xml")
    parser.add_argument(
        "--gradle-exit",
        type=int,
        default=None,
        help="exit status of the Gradle test task",
    )
    parser.add_argument(
        "--min-tests",
        type=int,
        default=None,
        help="fail if fewer than this many tests were executed",
    )
    return parser.parse_args()


def failure_message(node):
    message = (node.get("message") or "").strip()
    if message:
        return message
    return (node.text or "").strip().splitlines()[0] if node.text else ""


def main():
    args = parse_args()
    files = sorted(glob.glob(os.path.join(args.results, "TEST-*.xml")))
    if not files:
        print(f"::error::No JUnit XML files found in {args.results}")
        return 2

    total = skipped = 0
    failures = []

    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            print(f"::error::Cannot parse {path}: {error}")
            return 2

        for case in root.iter("testcase"):
            total += 1
            if case.find("skipped") is not None:
                skipped += 1
            key = (case.get("classname") or "", case.get("name") or "")
            for kind in ("failure", "error"):
                for node in case.findall(kind):
                    failures.append((key, failure_message(node)))

    print(f"### Unit test results: `{args.results}`\n")
    print(f"- Tests run: {total}")
    print(f"- Skipped: {skipped}")
    print(f"- Failures/errors: {len(failures)}")

    if failures:
        print(f"- **Failed tests: {len(failures)}**")
        for (classname, name), message in failures:
            print(f"  - `{classname}.{name}`")
            print(f"    > {message.splitlines()[0]}")

    if args.min_tests is not None and total - skipped < args.min_tests:
        print(
            f"\n::error title=Too few tests executed::"
            f"Expected at least {args.min_tests}, found {total - skipped} "
            f"({total} total, {skipped} skipped). "
            "The suite selection changed or tests were skipped."
        )
        return 1

    if failures:
        print("\n::error title=Unit test failures::See the reported failures above.")
        return 1

    if args.gradle_exit not in (None, 0):
        print(f"\n::error title=Gradle test task failed::Gradle exited {args.gradle_exit}.")
        return 1

    print("\n**Result: PASS** (no unit-test failures)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
