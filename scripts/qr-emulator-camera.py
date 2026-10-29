#!/usr/bin/env python3
"""Inject a QR image into an Android Emulator virtual camera.

This changes only a virtual-scene poster in the selected running emulator.
It does not restart an AVD, edit AVD settings, install an app, or deliver a
decoded value through an intent. Open Rivo Relay's scanner normally so its
camera and QR decoder process the poster pixels.

Examples:
  python3 scripts/qr-emulator-camera.py inject /absolute/path/pairing-qr.png
  python3 scripts/qr-emulator-camera.py restore

For end-to-end evidence, capture the app camera preview with the QR visible,
then the resulting project list and the service's successful pairing event.
Do not treat this script's success as evidence that QR decoding succeeded.
Keep pairing QR images out of source control and remove them after testing.
"""

import argparse
import hashlib
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default="emulator-5554", help="Target emulator serial")
    parser.add_argument("--surface", choices=("wall", "table"), default="wall", help="Virtual-scene poster")
    commands = parser.add_subparsers(dest="action", required=True)
    inject = commands.add_parser("inject", help="Load a host PNG or JPEG into the virtual camera scene")
    inject.add_argument("image", type=pathlib.Path)
    commands.add_parser("restore", help="Restore the selected poster's default image")
    args = parser.parse_args()

    if not args.serial.startswith("emulator-"):
        parser.error("This helper supports an Android Emulator, not a physical device.")
    adb = shutil.which("adb")
    if not adb:
        parser.error("adb was not found on PATH.")

    command = [adb, "-s", args.serial, "emu", "virtualscene-image", args.surface]
    if args.action == "inject":
        image = args.image.expanduser().resolve(strict=True)
        if not image.is_file():
            parser.error("The image must be a regular file.")
        with image.open("rb") as image_file:
            magic = image_file.read(8)
        if not (magic == b"\x89PNG\r\n\x1a\n" or magic.startswith(b"\xff\xd8\xff")):
            parser.error("Use a PNG or JPEG image with a clear QR quiet zone.")
        # The emulator caches textures by filename. Reusing pairing.png can
        # show the previous, already-consumed QR even after the file changes.
        data = image.read_bytes()
        cache = pathlib.Path(tempfile.gettempdir()) / f"rivo-relay-qr-{os.getuid()}"
        cache.mkdir(mode=0o700, exist_ok=True)
        cached_image = cache / (hashlib.sha256(data).hexdigest() + image.suffix)
        cached_image.write_bytes(data)
        cached_image.chmod(0o600)
        command.append(str(cached_image))

    result = subprocess.run(command, capture_output=True, text=True, timeout=15, check=False)
    output = (result.stdout + result.stderr).strip()
    if result.returncode or any(line.startswith(("KO", "ERROR", "error:")) for line in output.splitlines()):
        print(output or "Emulator console command failed.", file=sys.stderr)
        return 1
    if "OK" not in output.splitlines():
        print("The emulator did not confirm the camera-scene update.", file=sys.stderr)
        return 1
    if args.action == "inject":
        print(f"QR image loaded on {args.serial} {args.surface}. Open the app scanner and verify real camera decoding.")
    else:
        print(f"Restored the default {args.surface} poster on {args.serial}.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
