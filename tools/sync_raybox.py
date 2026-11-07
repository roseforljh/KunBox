"""Install a locally verified RayBox Android candidate without changing release locks."""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def install_candidate(source, project, allow_dirty=False, directory=None, phone_arm64=False):
    checker_path = source / "tools/check_android_artifacts.py"
    spec = importlib.util.spec_from_file_location("raybox_artifacts", checker_path)
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
    identity = checker.source_identity(source)
    if identity["dirty"] and not allow_dirty:
        raise ValueError("Dirty source requires explicit --allow-dirty")
    if phone_arm64 and not (allow_dirty and identity["dirty"]):
        raise ValueError("Phone arm64 artifacts require an explicit dirty local candidate")
    directory = directory or source / "artifacts" / ("libbox-candidate" if identity["dirty"] else "libbox")
    manifest_path = directory / "build.json"
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes.decode("utf-8-sig"))
    if manifest.get("schemaVersion") != 1 or manifest.get("repository") != "roseforljh/RayBox-rx":
        raise ValueError("Unexpected RayBox manifest source")
    for key in ("commit", "dirty", "sourceDigest", "sourceDigestAlgorithm"):
        if key not in manifest or manifest[key] != identity[key]:
            raise ValueError("Candidate does not match current source: " + key)
    expected_tag = None if identity["dirty"] else "android-" + identity["commit"]
    if "tag" not in manifest or manifest["tag"] != expected_tag:
        raise ValueError("Unexpected candidate tag")
    if not identity["dirty"]:
        tag_commit = subprocess.check_output(
            ["git", "-C", str(source), "rev-parse", "refs/tags/" + expected_tag + "^{commit}"], text=True
        ).strip()
        if tag_commit != identity["commit"]:
            raise ValueError("Local release tag points to a different commit")
    provenance = manifest.get("source", {})
    if (provenance.get("url") != "https://github.com/roseforljh/RayBox-rx/tree/" + identity["commit"] or
            provenance.get("runtimeLicense") != "docs/reference/runtime/LICENSE" or
            provenance.get("protocolLicense") != "docs/reference/protocol/LICENSE"):
        raise ValueError("Unexpected source provenance")
    if (not manifest.get("go", "").startswith("go version go1.27.3 ") or
            manifest.get("ndk") != "29.0.14206865" or manifest.get("gomobile") != "v0.1.13" or
            re.match(r"^(openjdk|java) 17[. ]", manifest.get("java", "")) is None):
        raise ValueError("Unexpected candidate toolchain")
    names = ("raybox.aar",) if phone_arm64 else ("raybox.aar", "raybox-legacy.aar")
    expected_api = {name: 21 if "legacy" in name else 24 for name in names}
    if manifest.get("androidApi") != expected_api:
        raise ValueError("Unexpected Android API contract")
    tags = manifest.get("buildTags", {})
    for name in names:
        values = tags.get(name)
        if (not isinstance(values, list) or not values or
                any(not isinstance(value, str) for value in values) or "with_tailscale" in values or
                name == "raybox-legacy.aar" and "with_naive_outbound" in values):
            raise ValueError("Unexpected candidate build tags: " + name)
    expected_abis = {"arm64-v8a"} if phone_arm64 else set(checker.ABIS)
    if set(manifest.get("architectures", [])) != expected_abis:
        raise ValueError("Candidate ABI selection differs from requested build")
    if manifest.get("rxDefault") is not False or manifest.get("oobDefault") is not False:
        raise ValueError("RX/OOB defaults must remain disabled")
    outputs = manifest.get("outputs")
    if not isinstance(outputs, list) or [item.get("name") for item in outputs] != list(names):
        raise ValueError("Manifest must identify exactly the requested AARs")
    libraries = project / "app/libs"
    if not libraries.is_dir():
        raise ValueError("KunBox app/libs directory is missing")
    with tempfile.TemporaryDirectory(prefix="raybox-install-", dir=libraries) as temporary:
        staging = Path(temporary)
        for entry in outputs:
            path = directory / entry["name"]
            report = checker.inspect_aar(path, require_safety=True, expected_abis=expected_abis)
            if entry != report:
                raise ValueError("Actual AAR report differs from manifest: " + entry["name"])
        for entry in outputs:
            name = entry["name"]
            shutil.copyfile(directory / name, staging / name)
            if sha256(staging / name) != entry["sha256"]:
                raise ValueError("Candidate changed while staging: " + name)
        (staging / "raybox-build.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        if checker.source_identity(source) != identity or manifest_path.read_bytes() != manifest_bytes:
            raise ValueError("Source changed while validating candidate")
        backup = libraries / "raybox-backup"
        if backup.exists():
            backup = backup / uuid.uuid4().hex
        backup.mkdir(parents=True, exist_ok=False)
        targets = tuple(entry["name"] for entry in outputs) + ("raybox-build.json",)
        existed = {name: (libraries / name).exists() for name in targets}
        for name in targets:
            if existed[name]:
                shutil.copy2(libraries / name, backup / name)
        installed = []
        try:
            for name in targets:
                os.replace(staging / name, libraries / name)
                installed.append(name)
        except BaseException:
            for name in reversed(installed):
                if existed[name]:
                    shutil.copy2(backup / name, libraries / name)
                else:
                    (libraries / name).unlink()
            raise
    return {"commit": identity["commit"], "dirty": identity["dirty"],
            "sourceDigest": identity["sourceDigest"], "sha256": outputs[0]["sha256"],
            "backup": str(backup)}


def install_release(source, project, lock_path):
    lock = json.loads(lock_path.read_text(encoding="utf-8-sig"))
    commit = lock.get("commit")
    if (lock.get("repository") != "roseforljh/RayBox-rx" or not isinstance(commit, str) or
            len(commit) != 40 or any(char not in "0123456789abcdef" for char in commit) or
            lock.get("tag") != "android-" + commit or lock.get("dirty") is not False):
        raise ValueError("Release lock must pin a clean, full RayBox commit and matching tag")
    actual = subprocess.check_output([
        "gh", "api", "repos/roseforljh/RayBox-rx/commits/" + lock["tag"], "--jq", ".sha"
    ], text=True).strip()
    if actual != commit:
        raise ValueError("Release tag does not point to locked commit")
    with tempfile.TemporaryDirectory(prefix="kunbox-raybox-release-") as temporary:
        directory = Path(temporary)
        subprocess.run([
            "gh", "release", "download", lock["tag"], "--repo", lock["repository"], "--dir", str(directory),
            "--pattern", "build.json"
        ], check=True)
        manifest = json.loads((directory / "build.json").read_text(encoding="utf-8-sig"))
        if manifest.get("commit") != commit or manifest.get("dirty") is not False:
            raise ValueError("Downloaded manifest differs from release lock")
        outputs = {entry["name"]: entry["sha256"] for entry in manifest.get("outputs", [])}
        if lock.get("sha256") != outputs:
            raise ValueError("Downloaded checksums differ from release lock")
        versioned = manifest.get("versionedOutputs") or []
        versioned_names = [entry["name"] for entry in versioned if isinstance(entry, dict) and entry.get("name")]
        download_names = versioned_names or list(outputs)
        command = [
            "gh", "release", "download", lock["tag"], "--repo", lock["repository"], "--dir", str(directory)
        ]
        for name in download_names:
            command.extend(["--pattern", name])
        subprocess.run(command, check=True)
        for entry in manifest.get("outputs", []):
            canonical = directory / entry["name"]
            if canonical.exists():
                continue
            source_name = next(
                (name for name in versioned_names if ("standard" in name and entry["name"] == "raybox.aar") or
                 ("legacy" in name and entry["name"] == "raybox-legacy.aar")),
                None
            )
            if source_name is None:
                raise ValueError("Downloaded release is missing " + entry["name"])
            shutil.copyfile(directory / source_name, canonical)
        return install_candidate(source, project, directory=directory)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Trusted local RayBox source checkout")
    parser.add_argument("--allow-dirty", action="store_true", help="Allow explicit local candidate only")
    parser.add_argument("--phone-arm64", action="store_true", help="Local dirty arm64 phone build only")
    parser.add_argument("--directory", type=Path, help="Verified local artifact directory")
    parser.add_argument("--release-lock", type=Path, help="Fetch and install an immutable clean release lock")
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    if args.release_lock:
        if args.allow_dirty or args.phone_arm64 or args.directory:
            parser.error("--release-lock cannot be combined with local candidate options")
        result = install_release(args.source.resolve(), args.project.resolve(), args.release_lock.resolve())
    else:
        result = install_candidate(
            args.source.resolve(), args.project.resolve(), args.allow_dirty,
            directory=args.directory, phone_arm64=args.phone_arm64
        )
    print(json.dumps(result, indent=2))
