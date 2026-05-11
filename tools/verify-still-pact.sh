#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

python3 - "$root" <<'PY'
from collections import Counter
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
name = root.name
manifest = root / "app/src/main/AndroidManifest.xml"
app_gradle = root / "app/build.gradle.kts"
readme = root / "README.md"

expected_permissions_by_repo = {
    "still-contacts": {
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
    },
    "still-dialer": {
        "android.permission.CALL_PHONE",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.MANAGE_OWN_CALLS",
        "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.POST_NOTIFICATIONS",
    },
    "still-sms": {
        "android.permission.SEND_SMS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.RECEIVE_MMS",
        "android.permission.RECEIVE_WAP_PUSH",
        "android.permission.READ_CONTACTS",
        "android.permission.POST_NOTIFICATIONS",
    },
    "still-voice": {
        "android.permission.RECORD_AUDIO",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.POST_NOTIFICATIONS",
    },
}
gradle_forbidden = [
    "com.google.firebase",
    "com.google.android.gms",
    "crashlytics",
    "analytics",
    "mixpanel",
    "amplitude",
]
gradle_file_names = {
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
    "gradle.properties",
    "libs.versions.toml",
}
skip_dirs = {".git", ".gradle", "build"}
android_name = "{http://schemas.android.com/apk/res/android}name"
number_words = {
    "no": 0,
    "zero": 0,
    "one": 1,
    "two": 2,
    "three": 3,
    "four": 4,
    "five": 5,
    "six": 6,
    "seven": 7,
    "eight": 8,
    "nine": 9,
    "ten": 10,
}


def rel(path: Path) -> str:
    return path.relative_to(root).as_posix()


def tag_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def strip_gradle_comments(path: Path, text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    stripped_lines = []
    for raw_line in text.splitlines():
        line = raw_line.lstrip()
        if path.suffix == ".toml":
            stripped_lines.append(raw_line.split("#", 1)[0])
        elif path.name.endswith(".properties"):
            if line.startswith("#") or line.startswith("!"):
                stripped_lines.append("")
            else:
                stripped_lines.append(raw_line.split("#", 1)[0].split("!", 1)[0])
        else:
            stripped_lines.append(raw_line.split("//", 1)[0])
    return "\n".join(stripped_lines)


def is_gradle_settings_or_catalog_file(path: Path) -> bool:
    if path.name in gradle_file_names:
        return True
    if path.name.endswith(".gradle") or path.name.endswith(".gradle.kts"):
        return True
    if path.name.endswith(".versions.toml"):
        return True
    return path.suffix == ".toml" and "gradle" in path.relative_to(root).parts


def iter_gradle_settings_or_catalog_files():
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [dirname for dirname in dirnames if dirname not in skip_dirs]
        current = Path(dirpath)
        for filename in filenames:
            path = current / filename
            if is_gradle_settings_or_catalog_file(path):
                yield path


def parse_manifest_permissions(path: Path):
    try:
        root_element = ET.parse(path).getroot()
    except ET.ParseError as exc:
        raise ValueError(f"{rel(path)} is not valid XML: {exc}") from exc

    requested = []
    declared = []
    for element in root_element.iter():
        name_attr = element.attrib.get(android_name) or element.attrib.get("name")
        if not name_attr:
            continue
        local_tag = tag_name(element.tag)
        if local_tag in {"uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"}:
            requested.append(name_attr)
        elif local_tag == "permission":
            declared.append(name_attr)
    return requested, declared


def duplicates(values):
    return sorted(value for value, count in Counter(values).items() if count > 1)


def format_values(values) -> str:
    return ", ".join(sorted(values)) if values else "(none)"


def parse_application_id(path: Path):
    if not path.is_file():
        return None
    text = strip_gradle_comments(path, path.read_text(encoding="utf-8"))
    match = re.search(r"\bapplicationId\s*=\s*[\"']([^\"']+)[\"']", text)
    return match.group(1) if match else None


def source_manifests():
    src = root / "app/src"
    if not src.is_dir():
        return []
    return sorted(path for path in src.rglob("AndroidManifest.xml") if path.is_file())


def merged_manifests():
    intermediates = root / "app/build/intermediates"
    if not intermediates.is_dir():
        return []
    patterns = [
        "merged_manifest/**/AndroidManifest.xml",
        "merged_manifests/**/AndroidManifest.xml",
    ]
    paths = []
    seen = set()
    for pattern in patterns:
        for path in intermediates.glob(pattern):
            resolved = path.resolve()
            if path.is_file() and resolved not in seen:
                seen.add(resolved)
                paths.append(path)
    return sorted(paths)


def readme_permission_count(path: Path):
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip().lower()
        if "permissions" not in line:
            continue
        if "|" in line and "what it guarantees" in line:
            continue
        if "no `internet` permission" in line or "no internet permission" in line:
            continue
        match = re.search(
            r"\b(no|zero|one|two|three|four|five|six|seven|eight|nine|ten|\d+)"
            r"\b(?:\s+\w+){0,4}\s+permissions\b",
            line,
        )
        if match:
            token = match.group(1)
            return int(token) if token.isdigit() else number_words[token]
    return None


errors = []
expected_permissions = expected_permissions_by_repo.get(name)
if expected_permissions is None:
    errors.append(f"{name}: no permission allowlist configured")

for required in (manifest, readme):
    if not required.is_file():
        errors.append(f"{name}: missing {rel(required)}")

application_id = parse_application_id(app_gradle)
if application_id is None:
    errors.append(f"{name}: could not find applicationId in {rel(app_gradle)}")

if manifest.is_file() and expected_permissions is not None:
    try:
        source_permissions, _source_declarations = parse_manifest_permissions(manifest)
    except ValueError as exc:
        errors.append(f"{name}: {exc}")
        source_permissions = []

    source_set = set(source_permissions)
    duplicate_permissions = duplicates(source_permissions)
    if duplicate_permissions:
        errors.append(
            f"{name}: duplicate source permissions: {format_values(duplicate_permissions)}"
        )
    unexpected = source_set - expected_permissions
    missing = expected_permissions - source_set
    if unexpected:
        errors.append(
            f"{name}: unexpected source permissions: {format_values(unexpected)}"
        )
    if missing:
        errors.append(f"{name}: missing source permissions: {format_values(missing)}")
    if not unexpected and not missing and len(source_permissions) != len(expected_permissions):
        errors.append(
            f"{name}: source permissions must match allowlist exactly; "
            f"found {format_values(source_permissions)}"
        )

    for source_manifest in source_manifests():
        if source_manifest == manifest:
            continue
        try:
            variant_permissions, variant_declarations = parse_manifest_permissions(
                source_manifest
            )
        except ValueError as exc:
            errors.append(f"{name}: {exc}")
            continue

        duplicate_variant = duplicates(variant_permissions)
        if duplicate_variant:
            errors.append(
                f"{name}: {rel(source_manifest)} duplicate permissions: "
                f"{format_values(duplicate_variant)}"
            )
        unexpected_variant = set(variant_permissions) - expected_permissions
        if unexpected_variant:
            errors.append(
                f"{name}: {rel(source_manifest)} unexpected source permissions: "
                f"{format_values(unexpected_variant)}"
            )
        if variant_declarations:
            errors.append(
                f"{name}: {rel(source_manifest)} unexpected permission declarations: "
                f"{format_values(variant_declarations)}"
            )

    manifest_count = len(source_permissions)
    stated_count = readme_permission_count(readme) if readme.is_file() else None
    if stated_count is None:
        errors.append(f"{name}: could not find stated README permission count")
    elif stated_count != manifest_count:
        errors.append(
            f"{name}: README states {stated_count} permissions, "
            f"manifest declares {manifest_count}"
        )

    if application_id is not None:
        dynamic_permission = (
            f"{application_id}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
        allowed_merged_permissions = expected_permissions | {dynamic_permission}
        for merged_manifest in merged_manifests():
            try:
                merged_permissions, declared_permissions = parse_manifest_permissions(
                    merged_manifest
                )
            except ValueError as exc:
                errors.append(f"{name}: {exc}")
                continue
            merged_set = set(merged_permissions)
            duplicate_merged = duplicates(merged_permissions)
            if duplicate_merged:
                errors.append(
                    f"{name}: {rel(merged_manifest)} duplicates permissions: "
                    f"{format_values(duplicate_merged)}"
                )
            missing_merged = expected_permissions - merged_set
            unexpected_merged = merged_set - allowed_merged_permissions
            unexpected_declarations = set(declared_permissions) - {dynamic_permission}
            if missing_merged:
                errors.append(
                    f"{name}: {rel(merged_manifest)} missing source permissions after merge: "
                    f"{format_values(missing_merged)}"
                )
            if unexpected_merged:
                errors.append(
                    f"{name}: {rel(merged_manifest)} has unexpected merged permissions: "
                    f"{format_values(unexpected_merged)}"
                )
            if unexpected_declarations:
                errors.append(
                    f"{name}: {rel(merged_manifest)} has unexpected permission declarations: "
                    f"{format_values(unexpected_declarations)}"
                )

for gradle_file in iter_gradle_settings_or_catalog_files():
    gradle_text = strip_gradle_comments(
        gradle_file, gradle_file.read_text(encoding="utf-8")
    ).casefold()
    for token in gradle_forbidden:
        if token.casefold() in gradle_text:
            errors.append(f"{name}: forbidden gradle token in {rel(gradle_file)}: {token}")

if errors:
    for error in errors:
        print(f"verify-still-pact: {error}", file=sys.stderr)
    sys.exit(1)

print(f"verify-still-pact: {name} clean")
PY
