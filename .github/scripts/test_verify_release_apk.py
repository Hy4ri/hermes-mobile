"""Black-box release APK checks using fake Android SDK executables.

Run with: python3 -m unittest discover -s .github/scripts -p 'test_verify_release_apk.py'
"""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


HELPER = Path(__file__).resolve().with_name("verify_release_apk.py")
CERTIFICATE = "0123456789abcdef" * 4
OTHER_CERTIFICATE = "fedcba9876543210" * 4
PACKAGE = "com.m57.hermescontrol"
VERSION_NAME = "1.2.3"
VERSION_CODE = "10203"


class VerifyReleaseApkCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="release apk test ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "release candidate.apk"
        self.apk.write_bytes(b"fixture APK bytes\x00\xff")
        self.original_apk = self.apk.read_bytes()
        self.log = self.root / "tool-calls.jsonl"
        self.apksigner = self.root / "fake apksigner"
        self.aapt = self.root / "fake aapt"
        self.write_tool(self.apksigner, self.signer_output())
        self.write_tool(self.aapt, self.badging_output())

    def signer_output(self, certificates=(CERTIFICATE,)):
        lines = ["Verifies", f"Number of signers: {len(certificates)}"]
        for index, certificate in enumerate(certificates, start=1):
            lines.extend([
                f"Signer #{index} certificate DN: CN=Release",
                f"Signer #{index} certificate SHA-256 digest: {certificate}",
                f"Signer #{index} certificate SHA-1 digest: {'a' * 40}",
            ])
        return "\n".join(lines) + "\n"

    def badging_output(self, package=PACKAGE, version_name=VERSION_NAME, version_code=VERSION_CODE):
        return (
            f"package: name='{package}' versionCode='{version_code}' "
            f"versionName='{version_name}' platformBuildVersionName='16'\n"
            "sdkVersion:'26'\n"
            "targetSdkVersion:'37'\n"
            "application-label:'Hermes Mobile'\n"
        )

    def write_tool(self, path, stdout="", stderr="", exit_code=0):
        source = (
            f"#!{sys.executable}\n"
            "import json, sys\n"
            f"with open({str(self.log)!r}, 'a', encoding='utf-8') as log:\n"
            "    log.write(json.dumps([sys.argv[0], *sys.argv[1:]]) + '\\n')\n"
            f"sys.stdout.write({stdout!r})\n"
            f"sys.stderr.write({stderr!r})\n"
            f"sys.exit({exit_code})\n"
        )
        path.write_text(source, encoding="utf-8")
        path.chmod(0o755)

    def invoke(self, certificate=CERTIFICATE, omit_certificate=False):
        command = [
            sys.executable, str(HELPER),
            "--apk", str(self.apk),
            "--version-name", VERSION_NAME,
            "--version-code", VERSION_CODE,
            "--apksigner", str(self.apksigner),
            "--aapt", str(self.aapt),
        ]
        if not omit_certificate:
            command.extend(["--certificate", certificate])
        environment = os.environ.copy()
        environment.pop("RELEASE_CERTIFICATE_SHA256", None)
        result = subprocess.run(
            command, capture_output=True, text=True, timeout=15,
            cwd=self.root, env=environment, check=False,
        )
        self.assertEqual(self.apk.read_bytes(), self.original_apk, "Verification changed the APK")
        return result

    def assert_rejected(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((result.stdout + result.stderr).strip(), "Failure needs a diagnostic")

    def test_valid_apk_uses_real_tools_with_exact_arguments(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertCountEqual(calls, [
            [str(self.apksigner), "verify", "--print-certs", str(self.apk)],
            [str(self.aapt), "dump", "badging", str(self.apk)],
        ])

    def test_certificate_normalizes_colons_and_case(self):
        colon_certificate = ":".join(CERTIFICATE[i:i + 2] for i in range(0, 64, 2)).upper()
        for expected, actual in [(colon_certificate, CERTIFICATE), (CERTIFICATE, colon_certificate)]:
            with self.subTest(expected=expected, actual=actual):
                self.write_tool(self.apksigner, self.signer_output((actual,)))
                result = self.invoke(certificate=expected)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_certificate_mismatch_is_rejected(self):
        self.write_tool(self.apksigner, self.signer_output((OTHER_CERTIFICATE,)))
        self.assert_rejected(self.invoke())

    def test_package_mismatch_is_rejected(self):
        self.write_tool(self.aapt, self.badging_output(package=PACKAGE + ".debug"))
        self.assert_rejected(self.invoke())

    def test_version_name_mismatch_is_rejected(self):
        self.write_tool(self.aapt, self.badging_output(version_name="1.2.4"))
        self.assert_rejected(self.invoke())

    def test_version_code_mismatch_is_rejected(self):
        self.write_tool(self.aapt, self.badging_output(version_code="10204"))
        self.assert_rejected(self.invoke())

    def test_missing_expected_certificate_is_rejected(self):
        self.assert_rejected(self.invoke(omit_certificate=True))

    def test_invalid_expected_certificates_are_rejected(self):
        for certificate in ("", "   ", "a" * 63, "a" * 65, "g" * 64, "not-a-fingerprint"):
            with self.subTest(certificate=certificate):
                self.assert_rejected(self.invoke(certificate=certificate))

    def test_missing_actual_sha256_certificate_is_rejected(self):
        self.write_tool(
            self.apksigner,
            "Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-1 digest: " + "a" * 40 + "\n",
        )
        self.assert_rejected(self.invoke())

    def test_unsigned_apk_is_rejected(self):
        self.write_tool(self.apksigner, stderr="DOES NOT VERIFY: No signatures found\n", exit_code=1)
        self.assert_rejected(self.invoke())

    def test_apksigner_failure_cannot_be_masked_by_matching_output(self):
        self.write_tool(self.apksigner, self.signer_output(), "verification failed\n", exit_code=2)
        self.assert_rejected(self.invoke())

    def test_aapt_failure_cannot_be_masked_by_matching_output(self):
        self.write_tool(self.aapt, self.badging_output(), "badging failed\n", exit_code=2)
        self.assert_rejected(self.invoke())

    def test_missing_tool_executables_are_rejected(self):
        for tool in (self.apksigner, self.aapt):
            with self.subTest(tool=tool.name):
                original = tool.read_text()
                tool.unlink()
                try:
                    self.assert_rejected(self.invoke())
                finally:
                    tool.write_text(original)
                    tool.chmod(0o755)

    def test_multiple_signers_are_rejected_even_if_one_matches(self):
        for certificates in (
            (CERTIFICATE, OTHER_CERTIFICATE),
            (OTHER_CERTIFICATE, CERTIFICATE),
            (CERTIFICATE, CERTIFICATE),
        ):
            with self.subTest(certificates=certificates):
                self.write_tool(self.apksigner, self.signer_output(certificates))
                self.assert_rejected(self.invoke())


if __name__ == "__main__":
    unittest.main()
