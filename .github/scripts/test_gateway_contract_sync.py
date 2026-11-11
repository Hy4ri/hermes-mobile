"""Offline regression tests for the scheduled gateway contract refresh."""

import importlib.util
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/gateway-contract-sync.yml"
DEST = "app/src/test/resources/gateway-contract"


def workflow_block(name, next_step):
    text = WORKFLOW.read_text()
    match = re.search(rf"        {name}: \|\n(.*?)(?=\n      {next_step}|\Z)", text, re.DOTALL)
    if not match:
        raise AssertionError(f"missing {name} block")
    return textwrap.dedent(match.group(1))


class GatewayContractSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        (self.repo / "scripts").mkdir()
        (self.repo / DEST).mkdir(parents=True)
        shutil.copy(ROOT / "scripts/sync-gateway-contract.sh", self.repo / "scripts")
        (self.repo / DEST / "openrpc.json").write_text('{"methods":[{"name":"old"}]}\n')
        (self.repo / DEST / "SOURCE").write_text("old provenance\n")
        self.git("init", "-q")
        self.git("add", ".")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "base")
        bindir = self.repo / "bin"
        bindir.mkdir()
        gh = bindir / "gh"
        gh.write_text(textwrap.dedent('''\
            #!/usr/bin/env bash
            set -eu
            if [[ ${FAIL_SYNC:-} == 1 ]]; then exit 1; fi
            case "$*" in
              *contents/*) printf '%s\\n' "$CONTRACT" ;;
              *commits/*--jq*.commit.committer.date*) echo 2026-11-11T00:00:00Z ;;
              *commits/*--jq*.sha*) echo abcdef ;;
              *) exit 1 ;;
            esac
        '''))
        gh.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{bindir}:{os.environ['PATH']}")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, check=True, text=True, capture_output=True).stdout

    def refresh(self, contract, fail=False):
        env = dict(self.env, CONTRACT=contract, FAIL_SYNC="1" if fail else "0")
        return subprocess.run(
            ["bash", "-eo", "pipefail", "-c", workflow_block("run", "#")],
            cwd=self.repo, env=env, text=True, capture_output=True,
        )

    def test_unchanged_contract_does_not_change_provenance(self):
        result = self.refresh('{"methods":[{"name":"old"}]}')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.git("status", "--porcelain", "--", DEST), "")

    def test_changed_contract_keeps_matching_provenance(self):
        result = self.refresh('{"methods":[{"name":"new"}]}')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('"new"', (self.repo / DEST / "openrpc.json").read_text())
        self.assertIn("sha: abcdef", (self.repo / DEST / "SOURCE").read_text())
        self.assertEqual(len(self.git("status", "--porcelain", "--", DEST).splitlines()), 2)

    def test_upstream_error_fails_the_refresh(self):
        result = self.refresh('{"methods":[{"name":"new"}]}', fail=True)
        self.assertNotEqual(result.returncode, 0)

    def test_invalid_contract_fails_the_refresh(self):
        result = self.refresh('{"methods":[]}')
        self.assertNotEqual(result.returncode, 0)

    def test_missing_token_fails_closed(self):
        section = WORKFLOW.read_text().split("- name: Require a token that can trigger PR CI", 1)[1]
        block = textwrap.dedent(section.split("        run: |\n", 1)[1].split("      - name:", 1)[0])
        result = subprocess.run(
            ["bash", "-eo", "pipefail", "-c", block],
            env=dict(os.environ, SYNC_TOKEN=""), text=True, capture_output=True,
        )
        self.assertNotEqual(result.returncode, 0)

    def test_generated_pr_body_passes_template_check(self):
        spec = importlib.util.spec_from_file_location("template", ROOT / ".github/scripts/check_pr_template.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        body = workflow_block("body", r"\Z")
        self.assertEqual(module.validate(body, title="chore: update gateway contract snapshot", base="main"), [])


if __name__ == "__main__":
    unittest.main()
