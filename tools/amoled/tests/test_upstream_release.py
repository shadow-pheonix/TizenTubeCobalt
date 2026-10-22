import copy
from argparse import Namespace
from contextlib import contextmanager
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_apk
import upstream_release as sync


class ReleasePlanningTests(unittest.TestCase):
    def setUp(self):
        self.manifest = sync.read_manifest(Path(sync.__file__).with_name("upstream-release.json"))
        self.release = {
            "id": self.manifest["upstream_release_id"],
            "tag_name": self.manifest["upstream_version"], "draft": False, "prerelease": False,
            "assets": [dict(id=data["id"], name=data["name"], digest="sha256:" + data["sha256"])
                       for data in self.manifest["assets"].values()],
        }

    def completed(self):
        return {
            "tag_name": self.manifest["upstream_version"] + "-amoled.1",
            "draft": False, "prerelease": False, "body": sync.marker(self.manifest),
            "assets": [{"name": name} for name in sync.REQUIRED_ASSETS],
        }

    def test_new_official_version_uses_its_actual_digests(self):
        release = copy.deepcopy(self.release)
        release["tag_name"] = "v9.8.7"
        release["id"] += 1
        release["assets"][0]["digest"] = "sha256:" + "a" * 64
        manifest = sync.make_manifest(release, "b" * 40)
        self.assertEqual(manifest["upstream_version"], "v9.8.7")
        self.assertEqual(manifest["upstream_commit"], "b" * 40)
        self.assertIn("a" * 64, [item["sha256"] for item in manifest["assets"].values()])
        self.assertEqual(sync.select_release(manifest, [self.completed()]), (True, "v9.8.7-amoled.1"))

    def test_completed_release_skips_repeat_build(self):
        self.assertEqual(sync.select_release(self.manifest, [self.completed()]),
                         (False, self.completed()["tag_name"]))

    def test_legacy_or_partial_release_does_not_count_as_success(self):
        for missing in sync.REQUIRED_ASSETS:
            release = self.completed()
            release["assets"] = [item for item in release["assets"] if item["name"] != missing]
            self.assertEqual(sync.select_release(self.manifest, [release]),
                             (True, self.manifest["upstream_version"] + "-amoled.2"))
        release = self.completed()
        release["body"] = "Initial manual build"
        self.assertTrue(sync.select_release(self.manifest, [release])[0])

    def test_interrupted_draft_is_reused(self):
        release = self.completed()
        release["draft"] = True
        self.assertEqual(sync.select_release(self.manifest, [release]), (True, release["tag_name"]))

    def test_force_build_chooses_new_revision(self):
        self.assertEqual(sync.select_release(self.manifest, [self.completed()], force=True),
                         (True, self.manifest["upstream_version"] + "-amoled.2"))

    def test_replaced_upstream_assets_are_detected(self):
        release = self.completed()
        self.manifest["assets"]["arm"]["sha256"] = "c" * 64
        self.assertTrue(sync.select_release(self.manifest, [release])[0])

    def test_updated_feature_bundle_requires_a_new_build(self):
        release = self.completed()
        with patch.object(sync, "THEME_URL", sync.THEME_URL.replace("amoled02", "amoled03")):
            self.assertTrue(sync.select_release(self.manifest, [release])[0])

    def test_missing_digests_or_apks_fail_before_build(self):
        for change in ("digest", "asset"):
            release = copy.deepcopy(self.release)
            if change == "digest":
                release["assets"][0].pop("digest")
            else:
                release["assets"].pop()
            with self.assertRaises(ValueError):
                sync.make_manifest(release, "a" * 40)

    def test_draft_and_prerelease_official_builds_are_excluded(self):
        for field in ("draft", "prerelease"):
            release = copy.deepcopy(self.release)
            release[field] = True
            with self.assertRaises(ValueError):
                sync.make_manifest(release, "a" * 40)

    def test_untrusted_tags_and_manifests_are_rejected(self):
        for tag in ("--help", "v1\nneeds_build=false", "v1;echo bad", "../main", None):
            with self.assertRaises(ValueError):
                sync.safe_tag(tag)
        manifest = copy.deepcopy(self.manifest)
        manifest["upstream_repository"] = "other/repo"
        with self.assertRaises(ValueError):
            sync.validate_manifest(manifest)

    @patch.object(build_apk.subprocess, "run")
    def test_valid_but_different_signer_is_rejected(self, command):
        command.return_value = subprocess.CompletedProcess([], 0, "Signer #1 certificate SHA-256 digest: " + "a" * 64)
        with self.assertRaises(ValueError):
            build_apk.verify_official_signature("apksigner", "input.apk")

    @patch.object(build_apk.subprocess, "run")
    def test_expected_official_signer_is_accepted(self, command):
        command.return_value = subprocess.CompletedProcess([], 0, "Signer #1 certificate SHA-256 digest: " + build_apk.UPSTREAM_SIGNER_SHA256)
        build_apk.verify_official_signature("apksigner", "input.apk")


class SourceMergeTests(unittest.TestCase):
    @contextmanager
    def repository(self, conflict=False):
        manifest = sync.read_manifest(Path(sync.__file__).with_name("upstream-release.json"))
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            os.chdir(directory)
            try:
                def git(*args):
                    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout.strip()
                git("init", "-b", "fork")
                git("config", "user.name", "Test")
                git("config", "user.email", "test@example.invalid")
                source = Path(sync.SOURCE_PATH)
                source.parent.mkdir(parents=True)
                source.write_text(sync.OLD_URL + "\n" + sync.OLD_URL + "\n")
                git("add", ".")
                git("commit", "-m", "base")
                base = git("rev-parse", "HEAD")
                source.write_text(source.read_text().replace(sync.OLD_URL, sync.THEME_URL))
                git("add", ".")
                git("commit", "-m", "AMOLED modification")
                fork = git("rev-parse", "HEAD")
                git("checkout", "-b", "official", base)
                Path("new-runtime-feature.txt").write_text("official update")
                if conflict:
                    source.write_text(source.read_text().replace(sync.OLD_URL, sync.OLD_URL + "?new-loader"))
                git("add", ".")
                git("commit", "-m", "official release")
                manifest["upstream_commit"] = git("rev-parse", "HEAD")
                git("checkout", "fork")
                Path("tools/amoled").mkdir(parents=True)
                Path("plan.json").write_text(json.dumps(manifest))
                yield Namespace(manifest=Path("plan.json"), base_commit=fork), source, git
            finally:
                os.chdir(previous)

    def no_network(self, *args, **kwargs):
        if args[:2] == ("git", "fetch"):
            return subprocess.CompletedProcess(args, 0)
        return subprocess.run([str(arg) for arg in args], check=True, capture_output=True, text=True, **kwargs)

    def test_new_official_source_is_merged_and_theme_survives(self):
        with self.repository() as (args, source, git):
            with patch.object(sync, "run", side_effect=self.no_network):
                sync.merge_source(args)
            self.assertTrue(Path("new-runtime-feature.txt").is_file())
            self.assertEqual(source.read_text().count(sync.THEME_URL), 2)
            self.assertNotIn(sync.OLD_URL, source.read_text())
            manifest = sync.read_manifest("tools/amoled/upstream-release.json")
            git("merge-base", "--is-ancestor", manifest["upstream_commit"], "HEAD")

    def test_conflicting_upstream_loader_stops_the_merge(self):
        with self.repository(conflict=True) as (args, source, git):
            with patch.object(sync, "run", side_effect=self.no_network) as command:
                with self.assertRaises(subprocess.CalledProcessError):
                    sync.merge_source(args)
            self.assertFalse(any(call.args[:2] == ("git", "push") for call in command.call_args_list))


if __name__ == "__main__":
    unittest.main()
