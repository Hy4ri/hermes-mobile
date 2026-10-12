"""Offline regression tests: all GitHub traffic is an explicit fake API boundary."""

import unittest
from copy import deepcopy
from unittest.mock import patch

import check_pr_template as template
import recover_pr_template as recovery


class CommentTests(unittest.TestCase):
    def test_valid_description_does_not_claim_ci_was_restarted(self):
        report = template.render([], "owner/repo")
        self.assertNotIn("CI is unblocked", report)


BODY = """## Summary
Recover CI after a description fix.
## Description
Only restart CI blocked by its template validation.
## Type of Change
- [x] 🔧 CI / chore
## How to test
Run offline regression tests and inspect the actual restart request.
## Checklist
- [x] Tests executed
"""


class FakeGitHub:
    """Models API state, not fabricated evidence of a real Actions run."""

    def __init__(self):
        self.pr = {
            "number": 42,
            "state": "open",
            "body": BODY,
            "title": "ci: recover template gate",
            "user": {"type": "User"},
            "base": {"ref": "dev"},
            "head": {
                "sha": "abc",
                "ref": "feature",
                "repo": {"full_name": "fork/repo"},
            },
        }
        self.runs = [
            {
                "id": 9,
                "run_attempt": 1,
                "event": "pull_request",
                "head_sha": "abc",
                "head_branch": "feature",
                "head_repository": {"full_name": "fork/repo"},
                "status": "completed",
                "conclusion": "failure",
                "pull_requests": [],
                "display_title": "Android CI PR #42",
            }
        ]
        self.jobs = [
            {
                "id": 11,
                "name": "PR Template",
                "conclusion": "failure",
                "steps": [{"name": "Validate PR description", "conclusion": "failure"}],
            },
            {"id": 12, "name": "Detect Changes", "conclusion": "success"},
            {"id": 13, "name": "Build Debug APK", "conclusion": "skipped"},
            {"id": 14, "name": "CI Summary", "conclusion": "failure"},
        ]
        self.files = []
        self.comments = []
        self.posts = []

    def get(self, path):
        if path == "pulls/42":
            return deepcopy(self.pr)
        if path == "actions/runs/9":
            return deepcopy(self.runs[0])
        raise AssertionError(path)

    def pages(self, path, key=None):
        if path.startswith("actions/workflows/android.yml/runs?"):
            return deepcopy(self.runs)
        if path.startswith("actions/runs/9/attempts/"):
            return deepcopy(self.jobs)
        if path == "pulls/42/files":
            return deepcopy(self.files)
        if path == "issues/42/comments":
            return deepcopy(self.comments)
        if path == "pulls?state=open&base=dev":
            return [deepcopy(self.pr)]
        raise AssertionError(path)

    def post(self, path, data):
        self.posts.append((path, data))
        if path == "issues/42/comments":
            comment = {
                "id": len(self.comments) + 1,
                "user": {"login": "github-actions[bot]"},
                **data,
            }
            self.comments.append(comment)
            return comment

    def update(self, path, data):
        self.comments[int(path.rsplit("/", 1)[1]) - 1].update(data)


class RecoveryTests(unittest.TestCase):
    def test_restarts_only_template_job_and_announces_after_acceptance(self):
        gh = FakeGitHub()
        self.assertEqual(recovery.recover(gh, 42), "requested")
        self.assertEqual(
            gh.posts[1], ("actions/jobs/11/rerun", {"enable_debug_logging": False})
        )
        self.assertEqual(gh.posts[0][0], "issues/42/comments")
        self.assertNotIn("CI restart requested", gh.posts[0][1]["body"])
        self.assertIn("CI restart requested", gh.comments[0]["body"])

    def test_comment_failure_after_restart_does_not_allow_duplicate(self):
        gh = FakeGitHub()
        with (
            patch.object(
                gh, "update", side_effect=OSError("comment update unavailable")
            ),
            self.assertRaises(OSError),
        ):
            recovery.recover(gh, 42)
        self.assertEqual(recovery.recover(gh, 42), "already-requested")
        restarts = [path for path, _ in gh.posts if path.endswith("/rerun")]
        self.assertEqual(len(restarts), 1)

    def test_completed_run_recovers_after_edit_during_initial_run(self):
        gh = FakeGitHub()
        gh.runs[0]["status"] = "in_progress"
        self.assertEqual(recovery.recover(gh, 42), "not-blocked")
        self.assertFalse(gh.posts)
        gh.runs[0]["status"] = "completed"
        self.assertEqual(recovery.recover(gh, 42), "requested")

    def test_duplicate_event_and_repeated_failure_do_not_loop(self):
        gh = FakeGitHub()
        recovery.recover(gh, 42)
        self.assertEqual(recovery.recover(gh, 42), "already-requested")
        gh.runs[0]["run_attempt"] = 2
        gh.jobs[0]["id"] = 21
        self.assertEqual(recovery.recover(gh, 42), "already-requested")
        self.assertEqual(len(gh.posts), 2)

    def test_new_correction_can_recover_same_run_again(self):
        gh = FakeGitHub()
        recovery.recover(gh, 42)
        gh.pr["body"] += "\nAdditional verification evidence."
        self.assertEqual(recovery.recover(gh, 42), "requested")

    def test_user_cannot_spoof_duplicate_receipt(self):
        gh = FakeGitHub()
        recovery.recover(gh, 42)
        gh.comments[0]["user"]["login"] = "contributor"
        self.assertEqual(recovery.recover(gh, 42), "requested")

    def test_no_mutation_for_ineligible_or_unblocked_cases(self):
        cases = {
            "closed": lambda g: g.pr.update(state="closed"),
            "wrong_base": lambda g: g.pr["base"].update(ref="main"),
            "bot": lambda g: g.pr["user"].update(type="Bot"),
            "invalid_body": lambda g: g.pr.update(body="not a template"),
            "invalid_title": lambda g: g.pr.update(title="not conventional"),
            "missing_screenshot": lambda g: g.files.append(
                {"filename": "app/src/main/java/com/m57/hermescontrol/ui/chat/Chat.kt"}
            ),
            "no_run": lambda g: g.runs.clear(),
            "stale_sha": lambda g: g.runs[0].update(head_sha="old"),
            "other_fork": lambda g: g.runs[0]["head_repository"].update(
                full_name="other/repo"
            ),
            "other_branch": lambda g: g.runs[0].update(head_branch="other"),
            "other_pr": lambda g: g.runs[0].update(pull_requests=[{"number": 43}]),
            "ambiguous_legacy_fork_run": lambda g: g.runs[0].pop("display_title"),
            "same_head_different_pr": lambda g: g.runs[0].update(
                display_title="Android CI PR #43"
            ),
            "push": lambda g: g.runs[0].update(event="push"),
            "green": lambda g: g.runs[0].update(conclusion="success"),
            "cancelled": lambda g: g.runs[0].update(conclusion="cancelled"),
            "awaiting_approval": lambda g: g.runs[0].update(status="action_required"),
            "queued": lambda g: g.runs[0].update(status="queued"),
            "missing_gate": lambda g: g.jobs.pop(0),
            "green_gate": lambda g: g.jobs[0].update(conclusion="success"),
            "checkout_failure": lambda g: g.jobs[0]["steps"][0].update(
                name="Checkout trusted script (base branch)"
            ),
            "test_failure": lambda g: g.jobs.append(
                {"name": "Unit & Integration Tests", "conclusion": "failure"}
            ),
            "changes_failure": lambda g: g.jobs[1].update(conclusion="failure"),
        }
        for name, mutate in cases.items():
            with self.subTest(name=name):
                gh = FakeGitHub()
                mutate(gh)
                recovery.recover(gh, 42)
                self.assertFalse(gh.posts)

    def test_only_latest_matching_run_matters(self):
        from copy import deepcopy

        gh = FakeGitHub()
        newer = deepcopy(gh.runs[0])
        newer.update(id=10, conclusion="success")
        gh.runs.append(newer)
        self.assertEqual(recovery.recover(gh, 42), "not-blocked")
        self.assertFalse(gh.posts)

    def test_pr_changes_before_write_are_fenced(self):
        for field, value in (
            ("body", "invalid"),
            ("state", "closed"),
            ("title", "changed"),
            ("sha", "new"),
        ):
            with self.subTest(field=field):
                gh = FakeGitHub()
                original = gh.get
                reads = 0

                def get(path, gh=gh, field=field, value=value, original=original):
                    nonlocal reads
                    if path == "pulls/42":
                        reads += 1
                        if reads == 2:
                            (gh.pr["head"] if field == "sha" else gh.pr)[field] = value
                    return original(path)

                gh.get = get
                self.assertEqual(recovery.recover(gh, 42), "changed")
                self.assertFalse(gh.posts)

    def test_concurrent_manual_rerun_is_fenced(self):
        gh = FakeGitHub()
        original = gh.pages
        reads = 0

        def pages(path, key=None):
            nonlocal reads
            if path.startswith("actions/workflows/"):
                reads += 1
                if reads == 2:
                    gh.runs[0].update(run_attempt=2, status="queued")
            return original(path, key)

        gh.pages = pages
        self.assertEqual(recovery.recover(gh, 42), "changed")
        self.assertFalse(gh.posts)

    def test_api_failure_fails_closed(self):
        for failing_path in ("pulls/42/files", "issues/42/comments"):
            with self.subTest(path=failing_path):
                gh = FakeGitHub()
                original = gh.pages

                def pages(path, key=None, failing_path=failing_path, original=original):
                    if path == failing_path:
                        raise OSError("API unavailable")
                    return original(path, key)

                gh.pages = pages
                with self.assertRaises(OSError):
                    recovery.recover(gh, 42)
                self.assertFalse(gh.posts)

    def test_rejected_restart_does_not_post_success(self):
        gh = FakeGitHub()
        original = gh.post

        def post(path, data):
            if path.endswith("/rerun"):
                raise OSError("Forbidden")
            return original(path, data)

        gh.post = post
        with self.assertRaises(OSError):
            recovery.recover(gh, 42)
        self.assertNotIn("CI restart requested", gh.comments[0]["body"])
        self.assertEqual(recovery.recover(gh, 42), "already-requested")


class ResolutionTests(unittest.TestCase):
    def test_edit_routes_by_pr_number(self):
        gh = FakeGitHub()
        self.assertEqual(
            recovery.resolve(gh, {"pull_request": gh.pr}, "pull_request_target"), [42]
        )
        self.assertEqual(recovery.resolve(gh, {}, "push"), [])

    def test_stale_head_other_workflow_and_push_are_ignored(self):
        for change in (
            {"head_sha": "old"},
            {"path": ".github/workflows/other.yml"},
            {"event": "push"},
        ):
            with self.subTest(change=change):
                gh = FakeGitHub()
                gh.runs[0]["path"] = ".github/workflows/android.yml"
                gh.runs[0].update(change)
                self.assertEqual(
                    recovery.resolve(gh, {"workflow_run": {"id": 9}}, "workflow_run"),
                    [],
                )

    def test_fork_completion_without_pull_requests_uses_live_open_prs(self):
        gh = FakeGitHub()
        event = {"workflow_run": {"id": 9, "pull_requests": []}}
        gh.runs[0]["path"] = ".github/workflows/android.yml"
        self.assertEqual(recovery.resolve(gh, event, "workflow_run"), [42])


class TransportTests(unittest.TestCase):
    def client(self):
        with patch.dict(
            "os.environ",
            {"GITHUB_REPOSITORY": "owner/repo", "GITHUB_TOKEN": "test-only"},
        ):
            return recovery.GitHub()

    def test_empty_accepted_restart_response(self):
        from io import BytesIO

        with patch.object(
            recovery.urllib.request, "urlopen", return_value=BytesIO(b"")
        ) as request:
            result = self.client().post(
                "actions/jobs/11/rerun", {"enable_debug_logging": False}
            )
        self.assertIsNone(result)
        sent = request.call_args.args[0]
        self.assertEqual(
            sent.full_url,
            "https://api.github.com/repos/owner/repo/actions/jobs/11/rerun",
        )
        self.assertEqual(sent.method, "POST")

    def test_list_pagination_preserves_filter(self):
        gh = self.client()
        for key in (None, "jobs"):
            with self.subTest(key=key):
                pages = [list(range(100)), [100]]
                payloads = [{key: rows} for rows in pages] if key else pages
                with patch.object(gh, "get", side_effect=payloads) as get:
                    self.assertEqual(
                        gh.pages("path?filter=latest", key), list(range(101))
                    )
                self.assertEqual(
                    get.call_args_list[1].args[0],
                    "path?filter=latest&per_page=100&page=2",
                )

    def test_empty_list_response_fails_closed(self):
        gh = self.client()
        with patch.object(gh, "get", return_value=None), self.assertRaises(ValueError):
            gh.pages("path")


if __name__ == "__main__":
    unittest.main()
