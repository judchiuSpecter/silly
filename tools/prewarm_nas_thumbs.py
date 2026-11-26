#!/usr/bin/env python3
"""Generate NAS thumbnails on the desktop and optionally push them to an Android device.

The phone's SFTP session is slow/unreliable for a 96k-photo archive, but the
thumbnail cache key is stable across installs. Generating the files here and
copying them into the app's cache lets the phone link them without downloading
anything.

Usage:
    python3 tools/prewarm_nas_thumbs.py --limit 5000 --push-device 10.0.0.20:33607

Only rows whose thumbCachePath is NULL are processed. Originals are staged on
disk (not /tmp, which is a small tmpfs) and removed when the run finishes.
"""

from __future__ import annotations

import argparse
import base64
import os
import shutil
import sqlite3
import struct
import subprocess
import sys
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from PIL import Image, ImageOps

APP_DATA = Path.home() / ".local/share/PhotoHub"
DB_PATH = APP_DATA / "photohub.db"
CRED_DIR = APP_DATA
THUMB_DIR = APP_DATA / "cache/thumbs"
STAGE = Path.home() / ".cache/photohub-prewarm"
SSH_HOST = "terrathunder.myqnapcloud.com"
SSH_USER = "admin"
SSH_PORT = 22
NAS_ROOT = "/share/Photos"
DEVICE_CACHE = "cache/photohub/thumbs"


def java_string_hash(value: str) -> int:
    """Kotlin String.hashCode() over UTF-16 code units, as unsigned hex."""
    h = 0
    for (unit,) in struct.iter_unpack(">H", value.encode("utf-16-be")):
        h = (31 * h + unit) & 0xFFFFFFFF
    return h


def thumb_filename(key: str) -> str:
    safe = "".join(c if (c.isalnum() or c in "-_") else "_" for c in key)[-48:]
    return f"{safe}-{java_string_hash(key):x}.jpg"


def nas_password(source_id: str) -> str:
    key = base64.b64decode((CRED_DIR / "credentials.key").read_text().strip())
    wanted = f"{source_id}.nasPassword="
    for line in (CRED_DIR / "credentials.properties").read_text().splitlines():
        if line.startswith(wanted):
            iv_b64, ct_b64 = line[len(wanted):].split(":", 1)
            return AESGCM(key).decrypt(
                base64.b64decode(iv_b64), base64.b64decode(ct_b64), None
            ).decode()
    raise SystemExit(f"No saved NAS password for {source_id}")


def write_askpass(password: str) -> Path:
    script = STAGE / "askpass.sh"
    secret = STAGE / "askpass.secret"
    secret.write_text(password + "\n")
    secret.chmod(0o600)
    script.write_text(f"#!/bin/sh\ncat {secret}\n")
    script.chmod(0o700)
    return script


def ssh_env(askpass: Path) -> dict[str, str]:
    env = os.environ.copy()
    env.update(
        SSH_ASKPASS=str(askpass),
        SSH_ASKPASS_REQUIRE="force",
        DISPLAY=env.get("DISPLAY", ":0"),
    )
    return env


def ssh_opts() -> list[str]:
    return [
        "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=/dev/null",
        "-o", "LogLevel=ERROR",
        "-o", "ConnectTimeout=5",
        "-o", "BatchMode=no",
        "-o", "PreferredAuthentications=password",
        "-o", "PubkeyAuthentication=no",
        "-o", "PasswordAuthentication=yes",
        "-o", "NumberOfPasswordPrompts=1",
    ]


def sftp_quote(path: str) -> str:
    return '"' + path.replace("\\", "\\\\").replace('"', '\\"') + '"'


def download_originals(remote_paths: list[tuple[str, Path]], askpass: Path) -> int:
    batch = STAGE / "download.batch"
    batch.write_text(
        "".join(
            f"get {sftp_quote(remote)} {sftp_quote(str(local))}\n"
            for remote, local in remote_paths
        )
    )
    result = subprocess.run(
        ["setsid", "sftp", *ssh_opts(), "-b", str(batch), f"{SSH_USER}@{SSH_HOST}"],
        env=ssh_env(askpass),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if result.returncode != 0:
        print(result.stdout[-4000:], file=sys.stderr)
    return sum(1 for _, local in remote_paths if local.is_file() and local.stat().st_size > 0)


def make_thumbnail(original: Path, target: Path, is_video: bool) -> bool:
    source = original
    temp = None
    try:
        if is_video:
            temp = original.with_suffix(".frame.jpg")
            subprocess.run(
                ["ffmpeg", "-y", "-v", "error", "-i", str(original),
                 "-frames:v", "1", "-vf", "scale=512:-1", str(temp)],
                check=True,
            )
            source = temp
        with Image.open(source) as image:
            image = ImageOps.exif_transpose(image)
            image.thumbnail((512, 512))
            if image.mode not in ("RGB", "L"):
                image = image.convert("RGB")
            image.save(target, "JPEG", quality=82, optimize=True)
        return True
    except Exception as exc:  # noqa: BLE001 - one bad photo must not stop the batch
        print(f"  skip {original.name}: {exc}", file=sys.stderr)
        return False
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-id", default="nas_c411a122")
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--push-device", help="adb device serial to copy thumbnails to")
    parser.add_argument("--keep-originals", action="store_true")
    args = parser.parse_args()

    STAGE.mkdir(parents=True, exist_ok=True)
    originals_dir = STAGE / "originals"
    originals_dir.mkdir(exist_ok=True)
    THUMB_DIR.mkdir(parents=True, exist_ok=True)

    db = sqlite3.connect(DB_PATH)
    rows = db.execute(
        "SELECT id, remoteId, thumbCacheKey, mimeType FROM MediaItem "
        "WHERE sourceId=? AND thumbCachePath IS NULL "
        "ORDER BY dateTaken DESC LIMIT ?",
        (args.source_id, args.limit),
    ).fetchall()
    if not rows:
        raise SystemExit("No rows need thumbnails")
    print(f"Selected {len(rows)} newest missing thumbnails")

    password = nas_password(args.source_id)
    askpass = write_askpass(password)

    downloads: list[tuple[str, Path]] = []
    jobs: list[tuple[str, str, Path, bool]] = []
    for row_id, remote_id, key, mime in rows:
        if not key:
            print(f"  skip {remote_id}: no thumbCacheKey", file=sys.stderr)
            continue
        name = thumb_filename(key)
        original = originals_dir / f"{name}.orig"
        thumb = THUMB_DIR / name
        if thumb.exists():
            db.execute(
                "UPDATE MediaItem SET thumbCachePath=?, syncState='THUMB_CACHED' WHERE id=?",
                (str(thumb), row_id),
            )
            continue
        downloads.append((remote_id, original))
        jobs.append((row_id, remote_id, thumb, mime.startswith("video/")))

    print(f"Downloading {len(downloads)} originals from NAS...")
    ok = download_originals(downloads, askpass)
    print(f"Downloaded {ok}/{len(downloads)} originals")

    saved = 0
    for index, (row_id, remote_id, thumb, is_video) in enumerate(jobs, 1):
        original = originals_dir / f"{thumb.name}.orig"
        if not original.exists():
            continue
        if make_thumbnail(original, thumb, is_video):
            db.execute(
                "UPDATE MediaItem SET thumbCachePath=?, syncState='THUMB_CACHED' WHERE id=?",
                (str(thumb), row_id),
            )
            saved += 1
            if not args.keep_originals:
                original.unlink(missing_ok=True)
        if index % 100 == 0:
            print(f"  generated {index}/{len(jobs)}")
    db.commit()
    print(f"Generated {saved} thumbnails in {THUMB_DIR}")

    if args.push_device:
        remote_stage = "/sdcard/photohub_thumbs"
        device = args.push_device
        subprocess.run(
            ["adb", "-s", device, "shell", f"rm -rf {remote_stage}; mkdir -p {remote_stage}"],
            check=True,
        )
        subprocess.run(
            ["adb", "-s", device, "push", f"{THUMB_DIR}/.", remote_stage],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        subprocess.run(
            ["adb", "-s", device, "shell",
             f"run-as dev.photohub.android mkdir -p {DEVICE_CACHE} && "
             f"run-as dev.photohub.android cp -r {remote_stage}/. {DEVICE_CACHE}/ && "
             f"rm -rf {remote_stage}"],
            check=True,
        )
        count = subprocess.run(
            ["adb", "-s", device, "shell",
             f"run-as dev.photohub.android find {DEVICE_CACHE} -type f | wc -l"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        print(f"Device now has {count} cached thumbnails")

    askpass.unlink(missing_ok=True)
    (STAGE / "askpass.secret").unlink(missing_ok=True)
    (STAGE / "download.batch").unlink(missing_ok=True)
    if not args.keep_originals:
        shutil.rmtree(originals_dir, ignore_errors=True)


if __name__ == "__main__":
    main()
