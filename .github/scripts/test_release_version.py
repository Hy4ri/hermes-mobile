"""Keep the candidate build and tag workflow on the same versionCode scheme."""

import unittest

from release_version import release_version


class ReleaseVersionTest(unittest.TestCase):
    def test_stable_and_prerelease_versions(self):
        for suffix, prerelease in (("", "false"), ("-rc.1", "true")):
            with self.subTest(suffix=suffix):
                version = "3.6.1" + suffix
                self.assertEqual(release_version("v" + version), {
                    "tag": "v" + version,
                    "version": version,
                    "code": 30601,
                    "prerelease": prerelease,
                })

    def test_android_version_code_bounds(self):
        self.assertEqual(release_version("v0.0.1")["code"], 1)
        self.assertEqual(release_version("v210000.0.0")["code"], 2_100_000_000)
        for tag in ("v0.0.0", "v210000.0.1", "v3.100.0", "v3.0.100"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release_version(tag)

    def test_malformed_tags_cannot_write_workflow_outputs(self):
        for tag in ("3.6.1", "v03.6.1", "v3.6.1-", "v3.6.1\ncode=1", "v3.6.1+build"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release_version(tag)
