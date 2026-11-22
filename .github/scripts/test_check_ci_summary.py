"""Regression cases for the required check's Actions inputs."""

import json
import os
from pathlib import Path
import subprocess
import sys
import unittest


SCRIPT = Path(__file__).with_name("check_ci_summary.py")


def jobs(*, code=True, tooling=False, template_required=True):
    return {
        "changes": {
            "result": "success",
            "outputs": {"code": str(code).lower(), "tooling": str(tooling).lower()},
        },
        "pr-template": {"result": "success" if template_required else "skipped"},
        "ktlint": {"result": "success" if code else "skipped"},
        "android-lint": {"result": "success" if code else "skipped"},
        "unit-tests": {"result": "success" if code else "skipped"},
        "build": {"result": "success" if code else "skipped"},
        "release-compile": {"result": "success" if code else "skipped"},
        "instrumented-tests": {"result": "success" if code else "skipped"},
        "tooling-tests": {"result": "success" if tooling else "skipped"},
    }


def run_gate(needs, *, template_required=True):
    return subprocess.run(
        [sys.executable, str(SCRIPT)],
        env={
            **os.environ,
            "CI_NEEDS": json.dumps(needs),
            "CI_TEMPLATE_REQUIRED": str(template_required).lower(),
        },
        capture_output=True,
        text=True,
        check=False,
    )


class CheckCISummaryTest(unittest.TestCase):
    def assert_fails(self, needs, *messages, template_required=True):
        result = run_gate(needs, template_required=template_required)
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1, output)
        for message in messages:
            self.assertIn(message, output)

    def test_successful_scopes(self):
        for code, tooling, required in [
            (True, True, True),
            (True, False, True),
            (False, False, True),
            (False, True, True),
            (False, False, False),
            (True, True, False),
        ]:
            with self.subTest(code=code, tooling=tooling, template=required):
                result = run_gate(
                    jobs(code=code, tooling=tooling, template_required=required),
                    template_required=required,
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_failed_change_detection_cannot_greenlight_skipped_jobs(self):
        for reason in ("failure", "cancelled", "skipped"):
            with self.subTest(reason=reason):
                needs = jobs(code=False, template_required=False)
                needs["changes"]["result"] = reason
                self.assert_fails(needs, "changes", reason, template_required=False)

    def test_missing_or_invalid_scope_is_not_docs_only(self):
        for flag in ("code", "tooling"):
            for value in ("", "no", False, None):
                with self.subTest(flag=flag, value=value):
                    needs = jobs(code=False)
                    if value is None:
                        del needs["changes"]["outputs"][flag]
                    else:
                        needs["changes"]["outputs"][flag] = value
                    self.assert_fails(needs, f"changes.{flag}")

    def test_required_android_jobs_cannot_skip_or_cancel(self):
        for job in (
            "ktlint", "android-lint", "unit-tests", "build", "release-compile",
            "instrumented-tests",
        ):
            for reason in ("skipped", "cancelled", "failure"):
                with self.subTest(job=job, reason=reason):
                    needs = jobs()
                    needs[job]["result"] = reason
                    self.assert_fails(needs, job, reason)

    def test_required_job_missing_from_needs_is_an_error(self):
        needs = jobs()
        del needs["instrumented-tests"]
        self.assert_fails(needs, "instrumented-tests", "missing")

    def test_new_dependency_must_have_an_explicit_policy(self):
        needs = jobs()
        needs["new-required-job"] = {"result": "failure"}
        self.assert_fails(needs, "new-required-job", "no result policy")

    def test_tooling_only_change_must_run_its_tests(self):
        needs = jobs(code=False, tooling=True)
        needs["tooling-tests"]["result"] = "skipped"
        self.assert_fails(needs, "tooling-tests", "skipped")

    def test_template_not_run_for_non_exempt_pr_is_an_error(self):
        needs = jobs(template_required=False)
        self.assert_fails(needs, "pr-template", "skipped")

    def test_failed_template_still_fails_with_expensive_checks_blocked(self):
        needs = jobs()
        needs["pr-template"]["result"] = "failure"
        for job in ("ktlint", "android-lint", "unit-tests", "instrumented-tests"):
            needs[job]["result"] = "skipped"
        self.assert_fails(needs, "pr-template", "failure")

    def test_out_of_scope_job_failure_is_not_hidden(self):
        needs = jobs(code=False)
        needs["unit-tests"]["result"] = "failure"
        self.assert_fails(needs, "unit-tests", "failure")


if __name__ == "__main__":
    unittest.main()
