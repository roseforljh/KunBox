import importlib.util
import json
import os
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("sync_raybox", Path(__file__).with_name("sync_raybox.py"))
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class CandidateInstallChecks(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.source = root / "raybox"
        self.project = root / "kunbox"
        self.directory = self.source / "artifacts/libbox-candidate"
        self.directory.mkdir(parents=True)
        self.libs = self.project / "app/libs"
        self.libs.mkdir(parents=True)
        (self.libs / "raybox.aar").write_bytes(b"original core")
        (self.libs / "raybox-legacy.aar").write_bytes(b"original legacy")
        self.identity = {"commit": "a" * 40, "dirty": True, "sourceDigest": "b" * 64,
                         "sourceDigestAlgorithm": "sha256-git-path-kind-content-v1"}
        reports = []
        for name in ("raybox.aar", "raybox-legacy.aar"):
            path = self.directory / name
            path.write_bytes(name.encode())
            reports.append({"name": name, "size": path.stat().st_size, "sha256": sync.sha256(path),
                            "nativeLibraries": []})
        self.reports = {report["name"]: report for report in reports}
        self.manifest = {"schemaVersion": 1, "repository": "roseforljh/RayBox-rx", **self.identity,
                         "tag": None, "architectures": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"],
                         "rxDefault": False, "oobDefault": False, "outputs": reports,
                         "go": "go version go1.27.3 windows/amd64", "ndk": "29.0.14206865",
                         "gomobile": "v0.1.13", "java": "openjdk 17.0.16",
                         "androidApi": {"raybox.aar": 24, "raybox-legacy.aar": 21},
                         "buildTags": {"raybox.aar": ["with_quic", "with_naive_outbound"],
                                       "raybox-legacy.aar": ["with_quic"]}, "source": {
                             "url": "https://github.com/roseforljh/RayBox-rx/tree/" + self.identity["commit"],
                             "runtimeLicense": "docs/reference/runtime/LICENSE",
                             "protocolLicense": "docs/reference/protocol/LICENSE"}}
        self.write_manifest()
        self.checker = types.SimpleNamespace(
            source_identity=Mock(return_value=self.identity),
            ABIS={abi: None for abi in self.manifest["architectures"]},
            inspect_aar=Mock(side_effect=lambda path, require_safety, expected_abis: self.reports[path.name]),
        )
        loader = types.SimpleNamespace(exec_module=lambda module: None)
        self.patches = (
            patch.object(sync.importlib.util, "spec_from_file_location", return_value=types.SimpleNamespace(loader=loader)),
            patch.object(sync.importlib.util, "module_from_spec", return_value=self.checker),
        )
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)

    def write_manifest(self):
        (self.directory / "build.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def test_installs_explicit_candidate_and_keeps_backup(self):
        result = sync.install_candidate(self.source, self.project, allow_dirty=True)
        self.assertTrue(result["dirty"])
        self.assertEqual((self.libs / "raybox.aar").read_bytes(), b"raybox.aar")
        self.assertEqual((self.libs / "raybox-legacy.aar").read_bytes(), b"raybox-legacy.aar")
        self.assertEqual((self.libs / "raybox-backup/raybox.aar").read_bytes(), b"original core")
        self.assertEqual((self.libs / "raybox-backup/raybox-legacy.aar").read_bytes(), b"original legacy")
        self.assertEqual(self.checker.inspect_aar.call_count, 2)
        for call in self.checker.inspect_aar.call_args_list:
            self.assertTrue(call.kwargs["require_safety"])

    def test_rejects_dirty_stale_and_failed_safety_without_replacement(self):
        with self.assertRaisesRegex(ValueError, "allow-dirty"):
            sync.install_candidate(self.source, self.project)
        self.manifest["sourceDigest"] = "c" * 64
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "sourceDigest"):
            sync.install_candidate(self.source, self.project, True)
        self.manifest.update(self.identity)
        self.write_manifest()
        self.checker.inspect_aar.side_effect = ValueError("Missing safety marker")
        with self.assertRaisesRegex(ValueError, "safety"):
            sync.install_candidate(self.source, self.project, True)
        self.assertEqual((self.libs / "raybox.aar").read_bytes(), b"original core")

    def test_reinstall_does_not_overwrite_original_backup(self):
        first = sync.install_candidate(self.source, self.project, True)
        second = sync.install_candidate(self.source, self.project, True)
        self.assertNotEqual(first["backup"], second["backup"])
        self.assertEqual((Path(first["backup"]) / "raybox.aar").read_bytes(), b"original core")
        self.assertEqual((Path(second["backup"]) / "raybox.aar").read_bytes(), b"raybox.aar")
        self.assertEqual((Path(second["backup"]) / "raybox-legacy.aar").read_bytes(), b"raybox-legacy.aar")

    def test_rejects_unproven_source_and_toolchain(self):
        self.manifest["source"]["url"] = "https://example.invalid/other"
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "provenance"):
            sync.install_candidate(self.source, self.project, True)
        self.manifest["source"]["url"] = "https://github.com/roseforljh/RayBox-rx/tree/" + self.identity["commit"]
        self.manifest["ndk"] = "28.0"
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "toolchain"):
            sync.install_candidate(self.source, self.project, True)
        self.assertEqual((self.libs / "raybox.aar").read_bytes(), b"original core")

    def test_rejects_incompatible_api_tags_and_java_before_installation(self):
        cases = (
            ("java", "openjdk 21.0.1", "toolchain"),
            ("androidApi", {"raybox.aar": 21, "raybox-legacy.aar": 21}, "API"),
            ("buildTags", {"raybox.aar": ["with_tailscale"], "raybox-legacy.aar": ["with_quic"]}, "tags"),
            ("buildTags", {"raybox.aar": ["with_quic"], "raybox-legacy.aar": ["with_naive_outbound"]}, "tags"),
        )
        for key, value, message in cases:
            with self.subTest(key=key, value=value):
                original = self.manifest[key]
                self.manifest[key] = value
                self.write_manifest()
                with self.assertRaisesRegex(ValueError, message):
                    sync.install_candidate(self.source, self.project, True)
                self.assertEqual((self.libs / "raybox.aar").read_bytes(), b"original core")
                self.manifest[key] = original

    def release_lock(self):
        lock = {"repository": "roseforljh/RayBox-rx", "commit": self.identity["commit"],
                "tag": "android-" + self.identity["commit"], "dirty": False,
                "sha256": {entry["name"]: entry["sha256"] for entry in self.manifest["outputs"]}}
        path = self.project / "raybox.source.json"
        path.write_text(json.dumps(lock), encoding="utf-8")
        return path

    def test_clean_release_installs_only_matching_source_and_hashes(self):
        self.identity["dirty"] = False
        self.manifest["dirty"] = False
        self.manifest["tag"] = "android-" + self.identity["commit"]
        def download(command, check):
            destination = Path(command[command.index("--dir") + 1])
            (destination / "build.json").write_text(json.dumps(self.manifest), encoding="utf-8")
            for name in ("raybox.aar", "raybox-legacy.aar"):
                (destination / name).write_bytes(name.encode())
        with patch.object(sync.subprocess, "check_output", return_value=self.identity["commit"]), \
                patch.object(sync.subprocess, "run", side_effect=download):
            result = sync.install_release(self.source, self.project, self.release_lock())
        self.assertFalse(result["dirty"])
        self.assertEqual((self.libs / "raybox.aar").read_bytes(), b"raybox.aar")
        self.assertEqual((self.libs / "raybox-legacy.aar").read_bytes(), b"raybox-legacy.aar")

    def test_release_rejects_mismatched_remote_tag_before_download(self):
        with patch.object(sync.subprocess, "check_output", return_value="c" * 40), \
                patch.object(sync.subprocess, "run") as download:
            with self.assertRaisesRegex(ValueError, "[Cc]hecked out RayBox source"):
                sync.install_release(self.source, self.project, self.release_lock())
            download.assert_not_called()
        self.assertEqual((self.libs / "raybox.aar").read_bytes(), b"original core")

    def test_clean_release_allows_generated_source_digest_difference(self):
        self.identity["dirty"] = False
        self.manifest["dirty"] = False
        self.manifest["tag"] = "android-" + self.identity["commit"]
        self.manifest["sourceDigest"] = "c" * 64
        self.write_manifest()
        def download(command, check):
            destination = Path(command[command.index("--dir") + 1])
            (destination / "build.json").write_text(json.dumps(self.manifest), encoding="utf-8")
            for name in ("raybox.aar", "raybox-legacy.aar"):
                (destination / name).write_bytes(name.encode())
        with patch.object(sync.subprocess, "check_output", return_value=self.identity["commit"]), \
                patch.object(sync.subprocess, "run", side_effect=download):
            result = sync.install_release(self.source, self.project, self.release_lock())
        self.assertFalse(result["dirty"])

    def test_release_rejects_changed_checksums_without_replacement(self):
        self.manifest["dirty"] = False
        def download(command, check):
            destination = Path(command[command.index("--dir") + 1])
            metadata = dict(self.manifest)
            metadata["outputs"] = [dict(entry, sha256="c" * 64) for entry in self.manifest["outputs"]]
            (destination / "build.json").write_text(json.dumps(metadata), encoding="utf-8")
        with patch.object(sync.subprocess, "check_output", return_value=self.identity["commit"]), \
                patch.object(sync.subprocess, "run", side_effect=download):
            with self.assertRaisesRegex(ValueError, "checksums"):
                sync.install_release(self.source, self.project, self.release_lock())
        self.assertEqual((self.libs / "raybox.aar").read_bytes(), b"original core")

    def test_release_cannot_install_dirty_candidate(self):
        def download(command, check):
            destination = Path(command[command.index("--dir") + 1])
            (destination / "build.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        with patch.object(sync.subprocess, "check_output", return_value=self.identity["commit"]), \
                patch.object(sync.subprocess, "run", side_effect=download):
            with self.assertRaisesRegex(ValueError, "manifest"):
                sync.install_release(self.source, self.project, self.release_lock())
        self.assertEqual((self.libs / "raybox.aar").read_bytes(), b"original core")

    def test_phone_arm64_requires_dirty_opt_in_and_validates_only_requested_abi(self):
        self.manifest["architectures"] = ["arm64-v8a"]
        self.manifest["androidApi"] = {"raybox.aar": 24}
        self.manifest["buildTags"] = {"raybox.aar": ["with_quic"]}
        self.manifest["outputs"] = [self.reports["raybox.aar"]]
        self.write_manifest()
        result = sync.install_candidate(self.source, self.project, True, phone_arm64=True)
        self.assertTrue(result["dirty"])
        self.checker.inspect_aar.assert_called_once_with(
            self.directory / "raybox.aar", require_safety=True, expected_abis={"arm64-v8a"}
        )
        self.identity["dirty"] = False
        self.manifest["dirty"] = False
        self.manifest["tag"] = "android-" + self.identity["commit"]
        self.write_manifest()
        with patch.object(sync.subprocess, "check_output", return_value=self.identity["commit"]):
            with self.assertRaisesRegex(ValueError, "dirty local candidate"):
                sync.install_candidate(self.source, self.project, True, phone_arm64=True)

    def test_second_file_failure_restores_original_core(self):
        original_replace = os.replace
        def replace(source, target):
            if Path(target).name == "raybox-build.json":
                raise OSError("simulated metadata installation failure")
            original_replace(source, target)
        with patch.object(sync.os, "replace", side_effect=replace):
            with self.assertRaisesRegex(OSError, "metadata"):
                sync.install_candidate(self.source, self.project, True)
        self.assertEqual((self.libs / "raybox.aar").read_bytes(), b"original core")
        self.assertFalse((self.libs / "raybox-build.json").exists())


if __name__ == "__main__":
    unittest.main()
