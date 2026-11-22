"""Report whether the jobs selected for this revision actually passed."""

import json
import os


TEMPLATE_DEPENDENT_JOBS = ("ktlint", "android-lint", "unit-tests", "instrumented-tests")


def check(needs, template_required):
    problems = []
    checked = set()

    def expect(job, expected):
        checked.add(job)
        result = needs.get(job, {}).get("result", "missing")
        if result != expected:
            problems.append(f"{job}: expected {expected}, got {result}")
        return result

    if expect("changes", "success") != "success":
        return problems

    flags = needs["changes"].get("outputs", {})
    for name, value in (
        ("changes.code", flags.get("code")),
        ("changes.tooling", flags.get("tooling")),
        ("template required", template_required),
    ):
        if value not in ("true", "false"):
            problems.append(f"{name}: expected an explicit true or false, got {value!r}")
    if problems:
        return problems

    required_template_result = "success" if template_required == "true" else "skipped"
    template = expect("pr-template", required_template_result)
    code = flags["code"] == "true"
    for job in TEMPLATE_DEPENDENT_JOBS:
        expect(job, "success" if code and template == required_template_result else "skipped")
    for job in ("build", "release-compile"):
        expect(job, "success" if code else "skipped")
    expect("tooling-tests", "success" if flags["tooling"] == "true" else "skipped")
    problems.extend(f"{job}: no result policy" for job in sorted(needs.keys() - checked))
    return problems


def main():
    needs = json.loads(os.environ["CI_NEEDS"])
    problems = check(needs, os.environ["CI_TEMPLATE_REQUIRED"])
    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        return 1
    print("All selected CI jobs passed; unselected jobs were skipped.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
