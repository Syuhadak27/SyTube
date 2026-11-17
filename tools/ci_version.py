#!/usr/bin/env python3
"""Resolve the version/tag for a CI run and (for stable pushes) bump sytube.properties + changelog.

Outputs via $GITHUB_OUTPUT:
    version, tag, bump, is_dev, dev_version_code, dev_version_name

The `dev` branch is a parallel app identity: it never bumps the stable version and always gets a
fresh versionCode (200000 + run number) so the dev updater offers each new build. All app identity
(package, label, version) lives in the root sytube.properties.
"""
import datetime
import os
import pathlib
import re
import subprocess
import sys

PROPS = pathlib.Path("sytube.properties")
CHANGELOG = pathlib.Path("CHANGELOG.md")
CHANGELOG_ES = pathlib.Path("CHANGELOG.es.md")


def read_properties(path):
    props = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        sep = "=" if "=" in line else ":" if ":" in line else None
        if sep is None:
            continue
        key, value = line.split(sep, 1)
        props[key.strip()] = value.strip()
    return props


def update_properties(path, updates):
    """Replace existing keys in place and append any new ones, keeping comments/order."""
    lines = path.read_text(encoding="utf-8").splitlines()
    seen = set()
    out = []
    for line in lines:
        stripped = line.strip()
        if stripped and not stripped.startswith(("#", "!")):
            sep = "=" if "=" in stripped else ":" if ":" in stripped else None
            if sep is not None:
                key = stripped.split(sep, 1)[0].strip()
                if key in updates:
                    out.append(f"{key}={updates[key]}")
                    seen.add(key)
                    continue
        out.append(line)
    for key, value in updates.items():
        if key not in seen:
            out.append(f"{key}={value}")
    path.write_text("\n".join(out) + "\n", encoding="utf-8")


def main():
    props = read_properties(PROPS)
    ref = os.environ.get("GITHUB_REF", "")
    ref_type = os.environ.get("GITHUB_REF_TYPE", "")
    ref_name = os.environ.get("GITHUB_REF_NAME", "")
    actor = os.environ.get("GITHUB_ACTOR", "")
    run_number = int(os.environ.get("GITHUB_RUN_NUMBER", "0") or 0)

    is_dev = ref_name == "dev"
    bump = True

    try:
        cur_version = props["app.versionName"]
    except KeyError:
        sys.exit("Missing app.versionName in sytube.properties")

    m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", cur_version)
    if not m:
        sys.exit(f"app.versionName must be MAJOR.MINOR.PATCH, got {cur_version!r}")

    major, minor, patch = int(m.group(1)), int(m.group(2)), int(m.group(3))

    if ref_type == "tag" and ref.startswith("refs/tags/v"):
        version = ref.split("/")[-1][1:]
        bump = False
    elif is_dev:
        version = cur_version
        bump = False
    elif cur_version == "1.20.0" and actor != "github-actions[bot]":
        # Explicit release version (the 1.20.0 restore): publish it as-is, don't bump to .1.
        version = cur_version
        bump = False
    else:
        patch += 1
        version = f"{major}.{minor}.{patch}"
        code = major * 10000 + minor * 100 + patch
        update_properties(
            PROPS,
            {"app.versionCode": code, "app.versionName": version},
        )
        write_changelog(version)

    dev_version_code = 200000 + run_number
    dev_version_name = f"{version}-dev.{run_number}"
    tag = f"dev-{run_number}" if is_dev else f"v{version}"

    out = pathlib.Path(os.environ["GITHUB_OUTPUT"])
    with out.open("a", encoding="utf-8") as f:
        f.write(f"version={version}\n")
        f.write(f"tag={tag}\n")
        f.write(f"bump={'true' if bump else 'false'}\n")
        f.write(f"is_dev={'true' if is_dev else 'false'}\n")
        f.write(f"dev_version_code={dev_version_code}\n")
        f.write(f"dev_version_name={dev_version_name}\n")
    print(f"version={version} tag={tag} bump={bump} dev={is_dev}")


def write_changelog(version):
    today = datetime.date.today().isoformat()
    if not CHANGELOG.exists():
        return
    text = CHANGELOG.read_text(encoding="utf-8")
    if re.search(r"^## " + re.escape(version) + r"\b", text, re.M):
        return

    msgs = ""
    try:
        out = subprocess.check_output(
            ["git", "log", "-5", "--pretty=format:- %s"], text=True)
        lines = [
            line for line in out.splitlines()
            if line.strip() and "[skip ci]" not in line and "Merge" not in line
        ]
        if lines:
            msgs = "\n".join(lines[:5])
    except Exception:
        pass
    if not msgs:
        msgs = "- Updates and improvements."

    entry = f"## {version} — {today}\n\n### Changed\n\n{msgs}\n\n"
    CHANGELOG.write_text(
        re.sub(r"^(# .*?\n\n)", lambda m: m.group(1) + entry, text, count=1),
        encoding="utf-8",
    )

    if CHANGELOG_ES.exists():
        es = CHANGELOG_ES.read_text(encoding="utf-8")
        if not re.search(r"^## " + re.escape(version) + r"\b", es, re.M):
            entry_es = f"## {version} — {today}\n\n### Cambios\n\n- Actualizaciones y mejoras.\n\n"
            CHANGELOG_ES.write_text(
                re.sub(r"^(# .*?\n\n)", lambda m: m.group(1) + entry_es, es, count=1),
                encoding="utf-8",
            )


if __name__ == "__main__":
    main()
