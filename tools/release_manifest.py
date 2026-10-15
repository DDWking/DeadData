#!/usr/bin/env python3
"""Build, validate and extract release notes for DeadData update manifests.

The manifest contract enforced here is the same one the app applies in
``app/src/main/java/app/deaddata/mobile/update/UpdateClient.java`` (``parseManifest``)
and the same one pinned by ``app/src/test/java/app/deaddata/mobile/update/UpdateManifestTest.java``:

    versionCode   integer >= 1
    versionName   non-blank after trim
    apkUrl        starts with https://
    sha256        exactly 64 lowercase hex characters, equal to the APK hash
    releaseNotes  free text (may be empty)

``tools/New-UpdateManifest.ps1`` mirrors these rules for Windows local releases,
so both producers emit manifests the app accepts.

Usage::

    python3 tools/release_manifest.py notes --version 1.4.1 [--changelog CHANGELOG.md]
    python3 tools/release_manifest.py build --apk DeadData-1.4.1.apk --version-code 10 \
        --version-name 1.4.1 --apk-url https://example/DeadData-1.4.1.apk \
        --notes-file release-notes.md --out update.json
    python3 tools/release_manifest.py validate --manifest update.json --apk DeadData-1.4.1.apk
"""

import argparse
import hashlib
import json
import os
import re
import sys

SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SECTION_PATTERN = r"^##\s*\[{0}\][^\n]*$"


class ManifestError(Exception):
    """Raised for user facing manifest problems."""


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def changelog_section(text, version):
    """Return the changelog body for version, falling back to Unreleased."""
    for name in (version, "Unreleased"):
        match = re.search(SECTION_PATTERN.format(re.escape(name)), text, re.MULTILINE)
        if match is None:
            continue
        rest = text[match.end():]
        stop = re.search(r"^##\s", rest, re.MULTILINE)
        if stop is not None:
            rest = rest[:stop.start()]
        body = rest.strip()
        if body:
            return body
    return None


def validate(manifest, apk_path=None):
    """Return a list of contract violations for an already parsed manifest."""
    errors = []
    if not isinstance(manifest, dict):
        return ["manifest must be a JSON object"]

    code = manifest.get("versionCode")
    if not isinstance(code, int) or isinstance(code, bool) or code < 1:
        errors.append("versionCode must be an integer >= 1")

    name = manifest.get("versionName")
    if not isinstance(name, str) or not name.strip():
        errors.append("versionName must be a non-blank string")

    url = manifest.get("apkUrl")
    if not isinstance(url, str) or not url.strip():
        errors.append("apkUrl must be a non-blank string")
    elif not url.strip().startswith("https://"):
        errors.append("apkUrl must use HTTPS")

    digest = manifest.get("sha256")
    if not isinstance(digest, str) or not SHA256_PATTERN.match(digest):
        errors.append("sha256 must be 64 lowercase hex characters")

    if "releaseNotes" in manifest and not isinstance(manifest["releaseNotes"], str):
        errors.append("releaseNotes must be a string")

    if apk_path is not None and not errors:
        actual = sha256_of(apk_path)
        if actual != digest:
            errors.append(
                "sha256 does not match {0} ({1} != {2})".format(apk_path, digest, actual)
            )
    return errors


def command_notes(args):
    path = args.changelog
    if not os.path.exists(path):
        raise ManifestError("changelog not found: {0}".format(path))
    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()
    notes = changelog_section(text, args.version)
    if notes is None:
        raise ManifestError(
            "CHANGELOG.md has no usable '[{0}]' or '[Unreleased]' section; "
            "add the release notes before publishing".format(args.version)
        )
    sys.stdout.write(notes + "\n")
    return 0


def command_build(args):
    if not os.path.exists(args.apk):
        raise ManifestError("apk not found: {0}".format(args.apk))
    if args.notes_file:
        if not os.path.exists(args.notes_file):
            raise ManifestError("notes file not found: {0}".format(args.notes_file))
        with open(args.notes_file, "r", encoding="utf-8") as handle:
            notes = handle.read().strip()
    else:
        notes = (args.notes or "").strip()

    manifest = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "apkUrl": args.apk_url,
        "sha256": sha256_of(args.apk),
        "releaseNotes": notes,
    }
    errors = validate(manifest)
    if errors:
        raise ManifestError("; ".join(errors))

    with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("Wrote {0} (versionCode={1} versionName={2})".format(
        os.path.abspath(args.out), manifest["versionCode"], manifest["versionName"]))
    return 0


def command_validate(args):
    if not os.path.exists(args.manifest):
        raise ManifestError("manifest not found: {0}".format(args.manifest))
    with open(args.manifest, "r", encoding="utf-8") as handle:
        try:
            manifest = json.load(handle)
        except ValueError as error:
            raise ManifestError("manifest is not valid JSON: {0}".format(error))

    errors = validate(manifest, args.apk if args.apk else None)
    if errors:
        raise ManifestError("; ".join(errors))
    print("Manifest is valid: {0}".format(os.path.abspath(args.manifest)))
    return 0


def configure_std_streams():
    """Force UTF-8 output so redirected notes stay byte-correct on any locale.

    On Windows a redirected stdout defaults to the ANSI code page (GBK here),
    which would silently corrupt release notes piped into a file.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8")
        except (ValueError, OSError):
            # Already wrapped or not reconfigurable; leave the stream alone.
            pass


def main(argv=None):
    configure_std_streams()
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    notes = sub.add_parser("notes", help="print the changelog section for a version")
    notes.add_argument("--version", required=True)
    notes.add_argument("--changelog", default="CHANGELOG.md")
    notes.set_defaults(func=command_notes)

    build = sub.add_parser("build", help="write an update.json for an APK")
    build.add_argument("--apk", required=True)
    build.add_argument("--version-code", type=int, required=True)
    build.add_argument("--version-name", required=True)
    build.add_argument("--apk-url", required=True)
    build.add_argument("--notes-file")
    build.add_argument("--notes")
    build.add_argument("--out", default="update.json")
    build.set_defaults(func=command_build)

    validate_cmd = sub.add_parser("validate", help="check an existing update.json")
    validate_cmd.add_argument("--manifest", required=True)
    validate_cmd.add_argument("--apk")
    validate_cmd.set_defaults(func=command_validate)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except ManifestError as error:
        print("error: {0}".format(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
