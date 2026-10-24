#!/usr/bin/env python3
"""Summarise JUnit XML and fail on unexpected unit-test failures.

The full :app suite has one known pre-existing failure on JDK 17:
FileReceiverActivityTest.testIsSharedTextAnUrl NPEs in Robolectric teardown.
--allow-baseline permits exactly that failure and only when the message still
matches; any other failure, a missing baseline test, or mismatched Gradle exit
status makes this script exit non-zero.
"""

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

BASELINE = ("com.termux.app.api.file.FileReceiverActivityTest", "testIsSharedTextAnUrl")
BASELINE_MARKERS = ("ShadowActivityThread.reset", "ActivityThread not set")


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("results", help="Gradle test-results directory containing TEST-*.xml")
    parser.add_argument(
        "--allow-baseline",
        action="store_true",
        help="allow the known FileReceiverActivityTest Robolectric teardown failure",
    )
    parser.add_argument(
        "--gradle-exit",
        type=int,
        default=None,
        help="exit status of the Gradle test task, if it was allowed to fail",
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
    unexpected = []
    baseline_matches = []
    baseline_executed = False
    baseline_failed = False

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
            if key == BASELINE and case.find("skipped") is None:
                baseline_executed = True
            for kind in ("failure", "error"):
                for node in case.findall(kind):
                    message = failure_message(node)
                    if key == BASELINE:
                        baseline_failed = True
                    if key == BASELINE and args.allow_baseline and all(
                        marker in message for marker in BASELINE_MARKERS
                    ):
                        baseline_matches.append((key, message))
                        continue
                    unexpected.append((key, message))

    print(f"### Unit test results: `{args.results}`\n")
    print(f"- Tests run: {total}")
    print(f"- Skipped: {skipped}")
    print(f"- Failures/errors: {len(unexpected) + len(baseline_matches)}")

    if baseline_matches:
        print(f"- Known baseline failures (allowed): {len(baseline_matches)}")
        for (classname, name), message in baseline_matches:
            print(f"  - `{classname}.{name}`")
            print(f"    > {message.splitlines()[0]}")
    elif args.allow_baseline and baseline_executed and not baseline_failed:
        print("- Known baseline test has no failure; the quarantine can be removed.")
    if unexpected:
        print(f"- **Unexpected failures: {len(unexpected)}**")

    if args.allow_baseline and not baseline_executed:
        print(
            "\n::error title=Baseline test missing::"
            "Expected com.termux.app.api.file.FileReceiverActivityTest.testIsSharedTextAnUrl "
            "in the results. The suite selection changed or the test was removed."
        )
        return 1

    if args.min_tests is not None and total - skipped < args.min_tests:
        print(
            f"\n::error title=Too few tests executed::"
            f"Expected at least {args.min_tests}, found {total - skipped} "
            f"({total} total, {skipped} skipped). "
            "The suite selection changed or tests were skipped."
        )
        return 1

    if unexpected:
        print("\n::error title=Unexpected unit-test failures::See the reported failures above.")
        for (classname, name), message in unexpected:
            print(f"\nFAILED `{classname}.{name}`\n> {message.splitlines()[0]}")
        return 1

    if args.gradle_exit not in (None, 0, 1):
        print(f"\n::error title=Gradle test task failed::Gradle exited {args.gradle_exit}.")
        return 1

    if args.gradle_exit == 1 and not (args.allow_baseline and baseline_matches):
        print(
            "\n::error title=Gradle test task failed::"
            "Gradle exited 1 without the allowed baseline failure."
        )
        return 1

    if args.gradle_exit:
        print(f"\nGradle exited {args.gradle_exit}; only the documented baseline failure is present.")
    print("\n**Result: PASS** (no unexpected unit-test failures)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
