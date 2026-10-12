"""Recover only template-blocked Android runs; never fetch or execute PR code.

Both description-edit and Android-completion events use this path. The workflow
serializes recovery per PR; the bot receipt prevents retries of the same run and
validated metadata even if Actions briefly serves stale run status.
"""

import argparse
import hashlib
import json
import os
import urllib.parse
import urllib.request

from check_pr_template import UI_PATHS, validate


class GitHub:
    def __init__(self):
        self.root = f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}/"
        self.token = os.environ["GITHUB_TOKEN"]

    def request(self, method, path, data=None):
        request = urllib.request.Request(
            self.root + path,
            method=method,
            data=json.dumps(data).encode() if data is not None else None,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/vnd.github+json",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read()
            return json.loads(body) if body else None

    def get(self, path):
        return self.request("GET", path)

    def post(self, path, data):
        return self.request("POST", path, data)

    def update(self, path, data):
        return self.request("PATCH", path, data)

    def pages(self, path, key=None):
        rows = []
        separator = "&" if "?" in path else "?"
        page = 1
        while True:
            result = self.get(f"{path}{separator}per_page=100&page={page}")
            if result is None:
                raise ValueError("Empty response from a list endpoint")
            batch = result[key] if key else result
            rows.extend(batch)
            if len(batch) < 100:
                return rows
            page += 1


def snapshot(pr):
    return (
        pr["head"]["sha"],
        pr["base"]["ref"],
        pr["state"],
        pr["title"],
        pr.get("body"),
    )


def matches_run(run, pr):
    return (
        run["event"] == "pull_request"
        and run["head_sha"] == pr["head"]["sha"]
        and run["head_branch"] == pr["head"]["ref"]
        and (run.get("head_repository") or {}).get("full_name")
        == (pr["head"].get("repo") or {}).get("full_name")
        and (
            any(p["number"] == pr["number"] for p in run.get("pull_requests", []))
            if run.get("pull_requests")
            else run.get("display_title") == f"Android CI PR #{pr['number']}"
        )
    )


def resolve(gh, event, event_name):
    if event_name == "pull_request_target":
        return [event["pull_request"]["number"]]
    if event_name != "workflow_run":
        return []
    run = gh.get(f"actions/runs/{int(event['workflow_run']['id'])}")
    if (
        run["event"] != "pull_request"
        or run.get("path") != ".github/workflows/android.yml"
    ):
        return []
    # Fork workflow_run payloads can have an empty pull_requests array. Do not
    # trust branch names alone or rely on that array for routing the completion.
    return [
        pr["number"]
        for pr in gh.pages("pulls?state=open&base=dev")
        if matches_run(run, pr)
    ]


def latest_run(gh, pr):
    query = urllib.parse.urlencode(
        {"event": "pull_request", "head_sha": pr["head"]["sha"]}
    )
    runs = gh.pages(f"actions/workflows/android.yml/runs?{query}", "workflow_runs")
    return max(
        (run for run in runs if matches_run(run, pr)),
        key=lambda run: run["id"],
        default=None,
    )


def recover(gh, number):
    pr = gh.get(f"pulls/{number}")
    if (
        pr["state"] != "open"
        or pr["base"]["ref"] != "dev"
        or pr["user"]["type"] == "Bot"
    ):
        return "ineligible"
    # Fail closed on file-list/API errors; no screenshot-check bypass for recovery.
    files = gh.pages(f"pulls/{number}/files")
    ui_files = [f["filename"] for f in files if UI_PATHS.match(f["filename"])]
    if validate(pr.get("body"), ui_files, pr["title"], pr["base"]["ref"]):
        return "invalid"
    run = latest_run(gh, pr)
    if not run or run["status"] != "completed" or run["conclusion"] != "failure":
        # A completion event will revisit an in-flight run with the LIVE description.
        return "not-blocked"
    jobs = gh.pages(
        f"actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs", "jobs"
    )
    gate = next((job for job in jobs if job["name"] == "PR Template"), None)
    if (
        not gate
        or gate["conclusion"] != "failure"
        or not any(
            step["name"] == "Validate PR description"
            and step["conclusion"] == "failure"
            for step in gate.get("steps", [])
        )
    ):
        return "not-template-failure"
    if any(
        job["conclusion"] not in ("success", "skipped")
        for job in jobs
        if job["name"] not in ("PR Template", "CI Summary")
    ):
        return "other-failure"

    digest = hashlib.sha256(json.dumps(snapshot(pr)).encode()).hexdigest()
    marker = f"<!-- pr-template-recovery:{run['id']}:{digest} -->"
    if any(
        comment["user"]["login"] == "github-actions[bot]" and marker in comment["body"]
        for comment in gh.pages(f"issues/{number}/comments")
    ):
        return "already-requested"
    # Fence head/body edits, manual reruns and new workflow runs immediately before mutation.
    if snapshot(gh.get(f"pulls/{number}")) != snapshot(pr):
        return "changed"
    fresh = latest_run(gh, pr)
    if not fresh or any(
        fresh[key] != run[key] for key in ("id", "run_attempt", "status", "conclusion")
    ):
        return "changed"
    # Reserve before requesting: if the final comment update fails, repeated
    # completion events must not create an automatic restart loop (#1506).
    receipt = gh.post(
        f"issues/{number}/comments",
        {
            "body": (
                f"{marker}\nPR description matches the template. One CI recovery attempt is reserved. "
                "Check PR Template Recovery in Actions for its outcome; a restart is not confirmed yet."
            )
        },
    )
    gh.post(f"actions/jobs/{gate['id']}/rerun", {"enable_debug_logging": False})
    gh.update(
        f"issues/comments/{receipt['id']}",
        {
            "body": (
                f"{marker}\n✅ PR description matches the template. CI restart requested "
                "for the failed PR Template job and its dependents. "
                "This is not a passing CI result; follow the Android CI run in Actions."
            )
        },
    )
    return "requested"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--pr", type=int)
    mode.add_argument("--resolve", action="store_true")
    args = parser.parse_args()
    gh = GitHub()
    if args.resolve:
        with open(os.environ["GITHUB_EVENT_PATH"]) as event_file:
            numbers = resolve(
                gh, json.load(event_file), os.environ["GITHUB_EVENT_NAME"]
            )
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"prs={json.dumps(numbers)}\n")
    else:
        print(recover(gh, args.pr))
