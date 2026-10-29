#!/usr/bin/env bash
# Fresh live acceptance workflow. This intentionally clears ONLY dev.rivo.relay.
# Requires an already running emulator, Relay, and native Codex forwarding.
set -euo pipefail
umask 077

RIVO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RIVO_SERIAL=emulator-5554
RIVO_WORKSPACE="$RIVO_ROOT/workspace"
RIVO_RELAY_URL=http://127.0.0.1:18765
RIVO_NODE_URL=http://10.0.2.2:18765
RIVO_PROJECT_NAME='Rivo Relay Validation'
RIVO_PYTHON="$RIVO_ROOT/.venv/bin/python"
RIVO_PROTO_DIR="$RIVO_ROOT/workspace/emulator-proto"
RIVO_DISCOVERY=
RIVO_RESTORE_POSTER=
RIVO_OUT=
RIVO_BUILD=0

usage() {
  cat <<'HELP'
Usage: scripts/verify-emulator.sh [options]

FRESH TEST WORKFLOW: installs the debug/test APKs and runs
  adb -s <serial> shell pm clear dev.rivo.relay
This deletes this test App's login/local data. It then pairs by actual camera
decoding, creates a native conversation, and dispatches a temporary todo in the
named existing project. Native test conversations remain for Desktop review.
Use an isolated validation project. No emulator or user service is stopped.

Options:
  --serial SERIAL         Running emulator-* (default: emulator-5554)
  --workspace DIR         Workspace of the running Relay (default: workspace)
  --relay-url URL         Host health URL base (default: http://127.0.0.1:18765)
  --node-url URL          QR's phone URL base (default: http://10.0.2.2:18765)
  --project-name NAME     Existing isolated project (default: Rivo Relay Validation)
  --out DIR              New output directory; refuses an existing directory
  --build                Build debug/test APKs first; default uses existing APKs
  --python PATH          Project Python 3.11+ (default: .venv/bin/python)
  --proto-dir DIR        Generated emulator protobufs (default: workspace/emulator-proto)
  --discovery FILE       Emulator pid_*.ini; otherwise discover matching serial
  --restore-poster IMAGE Restore this custom wall image on exit; otherwise default
  -h, --help             Show help without contacting the emulator

Camera preset: SDK Toren1BD Medium Phone, position -3.307 .7 .986,
rotation 5 -143 0. Other AVD geometry may require a different preset.
The original physical pose is saved/restored. The console cannot report the
previous custom wall image: supply --restore-poster if one must be preserved.

Relay/native forwarding must already be running. This script does not install
dependencies, change certificate stores, disable gRPC authentication, or add
network security exceptions. QR/token material is private and removed on exit.
Only credential-free instrumentation status lines and post-login screenshots
are retained; detailed failure text is intentionally not retained.
HELP
}

die() { printf '%s\n' "$*" >&2; exit 1; }
need_value() { [ "$#" -ge 2 ] || die "Missing value for $1"; }
while [ "$#" -gt 0 ]; do
  case "$1" in
    --serial) need_value "$@"; RIVO_SERIAL=$2; shift 2 ;;
    --workspace) need_value "$@"; RIVO_WORKSPACE=$2; shift 2 ;;
    --relay-url) need_value "$@"; RIVO_RELAY_URL=$2; shift 2 ;;
    --node-url) need_value "$@"; RIVO_NODE_URL=$2; shift 2 ;;
    --project-name) need_value "$@"; RIVO_PROJECT_NAME=$2; shift 2 ;;
    --out) need_value "$@"; RIVO_OUT=$2; shift 2 ;;
    --python) need_value "$@"; RIVO_PYTHON=$2; shift 2 ;;
    --proto-dir) need_value "$@"; RIVO_PROTO_DIR=$2; shift 2 ;;
    --discovery) need_value "$@"; RIVO_DISCOVERY=$2; shift 2 ;;
    --restore-poster) need_value "$@"; RIVO_RESTORE_POSTER=$2; shift 2 ;;
    --build) RIVO_BUILD=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done
[[ "$RIVO_SERIAL" =~ ^emulator-[0-9]+$ ]] || die 'Only an emulator-NNNN serial is allowed.'
[ -n "$RIVO_PROJECT_NAME" ] || die 'An isolated project name is required.'
[ -x "$RIVO_PYTHON" ] || die 'Create the project Python environment first: python3.11+ -m venv .venv && .venv/bin/pip install -e ./server'
RIVO_SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$HOME/Library/Android/sdk"}}
if ! command -v adb >/dev/null 2>&1; then
  [ -x "$RIVO_SDK/platform-tools/adb" ] || die 'adb is missing; add Android SDK platform-tools to PATH.'
  export PATH="$RIVO_SDK/platform-tools:$PATH"
fi
RIVO_ADB=$(command -v adb)

if ! "$RIVO_PYTHON" - "$RIVO_PROTO_DIR" <<'PY'
import importlib, sys
from pathlib import Path
if sys.version_info < (3, 11):
    raise SystemExit('Use the project Python 3.11+ environment.')
sys.path.insert(0, str(Path(sys.argv[1]).resolve()))
for module in ('rivo_relay', 'qrcode', 'PIL', 'grpc', 'jwt', 'cryptography',
               'emulator_controller_pb2', 'emulator_controller_pb2_grpc'):
    try:
        importlib.import_module(module)
    except (ImportError, RuntimeError):
        raise SystemExit('Missing or incompatible dependency: ' + module)
PY
then
  cat >&2 <<'HELP'
Install into the project virtualenv explicitly (nothing was installed):
  .venv/bin/python -m pip install -e './server[emulator]'
Generate bindings from your installed Android Emulator SDK proto:
  mkdir -p workspace/emulator-proto
  .venv/bin/python -m grpc_tools.protoc \
    -I "$ANDROID_SDK_ROOT/emulator/lib" \
    --python_out=workspace/emulator-proto --grpc_python_out=workspace/emulator-proto \
    "$ANDROID_SDK_ROOT/emulator/lib/emulator_controller.proto"
Set ANDROID_SDK_ROOT to your SDK directory for those commands. If using
--proto-dir or --python, substitute those paths. Do not disable gRPC auth.
HELP
  exit 1
fi

RIVO_DISCOVERY=$("$RIVO_PYTHON" - "$RIVO_SERIAL" "$RIVO_DISCOVERY" <<'PY'
import getpass, os, sys, tempfile
from pathlib import Path
serial, explicit = sys.argv[1:]
roots = [Path.home() / 'Library/Caches/TemporaryItems/avd/running',
         Path(tempfile.gettempdir()) / 'avd/running',
         Path('/run/user') / str(os.getuid()) / 'avd/running',
         Path('/tmp') / ('android-' + getpass.getuser()) / 'avd/running']
if os.environ.get('XDG_RUNTIME_DIR'):
    roots.append(Path(os.environ['XDG_RUNTIME_DIR']) / 'avd/running')
candidates = [Path(explicit)] if explicit else list({p for r in roots for p in r.glob('pid_*.ini')})
matches = []
for path in candidates:
    try:
        config = dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)
        if config.get('port.serial') != serial.removeprefix('emulator-'):
            continue
        if not config.get('grpc.port', '').isdigit() or not Path(config.get('grpc.jwks', '')).is_dir():
            continue
        if path.stem.startswith('pid_'):
            os.kill(int(path.stem[4:]), 0)
        matches.append(path.resolve())
    except (OSError, ValueError):
        continue
if len(matches) != 1:
    raise SystemExit('Cannot find exactly one live authenticated emulator discovery file; pass --discovery pid_*.ini for this serial.')
print(matches[0])
PY
) || exit 1

[ "$("$RIVO_ADB" -s "$RIVO_SERIAL" get-state 2>/dev/null)" = device ] || die 'The selected emulator is not already running/ready.'
[ "$("$RIVO_ADB" -s "$RIVO_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] || die 'Wait for this emulator to finish booting, then run again.'
[ -z "$RIVO_RESTORE_POSTER" ] || [ -f "$RIVO_RESTORE_POSTER" ] || die 'The restore poster image does not exist.'

# Validate URL rules, the service workspace lock, and health before clearing App data.
"$RIVO_PYTHON" - "$RIVO_WORKSPACE" "$RIVO_RELAY_URL" "$RIVO_NODE_URL" <<'PY'
import fcntl, json, sys, urllib.request
from pathlib import Path
from rivo_relay.config import Config
workspace, host_url, node_url = sys.argv[1:]
try:
    for url in (host_url, node_url):
        Config(workspace=Path(workspace), public_url=url).validate()
    if not (Path(workspace) / 'data/relay.sqlite3').is_file():
        raise ValueError()
    with (Path(workspace) / 'data/serve.lock').open('r') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            pass
        else:
            raise ValueError()
    with urllib.request.urlopen(host_url.rstrip('/') + '/health', timeout=5) as response:
        if response.status != 200 or json.load(response).get('status') != 'ok':
            raise ValueError()
except Exception:
    raise SystemExit('Relay health/workspace check failed. Start Relay/native forwarding first and use its exact workspace and URL; TLS verification stays enabled.')
PY

RIVO_TEMP=$(mktemp -d "${TMPDIR:-/tmp}/rivo-emulator-verify.XXXXXXXX")
if [ -z "$RIVO_OUT" ]; then
  RIVO_OUT="$RIVO_WORKSPACE/artifacts/emulator-$(date -u +%Y%m%dT%H%M%SZ)-$$"
fi
[ ! -e "$RIVO_OUT" ] && [ ! -L "$RIVO_OUT" ] || { rm -rf -- "$RIVO_TEMP"; die 'Output directory already exists; choose a new --out directory.'; }
mkdir -p -- "$RIVO_OUT/logs" "$RIVO_OUT/screenshots"
RIVO_POSTER_CHANGED=0
RIVO_APP_CLEARED=0
RIVO_QR_CACHE=
RIVO_TESTS_PASSED=0

collect_evidence() {
  [ "$RIVO_APP_CLEARED" = 1 ] || return 0
  for name in qr-paired-projects native-conversation todo-native-dispatch; do
    "$RIVO_ADB" -s "$RIVO_SERIAL" pull \
      "/sdcard/Android/data/dev.rivo.relay/files/verification/$name.png" \
      "$RIVO_OUT/screenshots/$name.png" >/dev/null 2>&1 || true
  done
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  collect_evidence
  if [ -s "$RIVO_TEMP/original-pose.json" ]; then
    "$RIVO_PYTHON" "$RIVO_ROOT/scripts/emulator-camera-pose.py" \
      --discovery "$RIVO_DISCOVERY" --proto-dir "$RIVO_PROTO_DIR" \
      --restore "$RIVO_TEMP/original-pose.json" >/dev/null 2>&1
    if [ "$?" -ne 0 ]; then
      cp "$RIVO_TEMP/original-pose.json" "$RIVO_OUT/unrestored-pose.json"
      printf '%s\n' 'Could not restore original camera pose; saved it as unrestored-pose.json for recovery.' >&2
      status=1
    fi
  fi
  if [ "$RIVO_POSTER_CHANGED" = 1 ]; then
    if [ -n "$RIVO_RESTORE_POSTER" ]; then
      "$RIVO_PYTHON" "$RIVO_ROOT/scripts/qr-emulator-camera.py" --serial "$RIVO_SERIAL" inject "$RIVO_RESTORE_POSTER" >/dev/null 2>&1
    else
      "$RIVO_PYTHON" "$RIVO_ROOT/scripts/qr-emulator-camera.py" --serial "$RIVO_SERIAL" restore >/dev/null 2>&1
    fi
    if [ "$?" -ne 0 ]; then printf '%s\n' 'Could not restore the wall poster.' >&2; status=1; fi
  fi
  # Delete only this run's fresh QR texture cache; other runs' files are untouched.
  [ -z "$RIVO_QR_CACHE" ] || rm -f -- "$RIVO_QR_CACHE"
  rm -rf -- "$RIVO_TEMP"
  printf 'Tests passed: %s/3. Evidence: %s\n' "$RIVO_TESTS_PASSED" "$RIVO_OUT"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [ "$RIVO_BUILD" = 1 ]; then
  printf '%s\n' 'Building debug and instrumentation APKs…'
  (cd "$RIVO_ROOT/client/android" && ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest) \
    >"$RIVO_TEMP/build.log" 2>&1 || die 'APK build failed. Run the Gradle build separately for diagnostics; no App data was cleared.'
fi
RIVO_APP_APK="$RIVO_ROOT/client/android/app/build/outputs/apk/debug/app-debug.apk"
RIVO_TEST_APK="$RIVO_ROOT/client/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[ -f "$RIVO_APP_APK" ] && [ -f "$RIVO_TEST_APK" ] || die 'Debug/test APKs are missing. Run with --build or build them first.'
printf '%s\n' 'Fresh workflow: installing APKs, then clearing ONLY dev.rivo.relay local App data.'
"$RIVO_ADB" -s "$RIVO_SERIAL" install -r -t "$RIVO_APP_APK" >"$RIVO_TEMP/install-app.log" 2>&1 || die 'Debug APK installation failed.'
"$RIVO_ADB" -s "$RIVO_SERIAL" install -r -t "$RIVO_TEST_APK" >"$RIVO_TEMP/install-test.log" 2>&1 || die 'Test APK installation failed.'
RIVO_CLEAR_RESULT=$("$RIVO_ADB" -s "$RIVO_SERIAL" shell pm clear dev.rivo.relay 2>/dev/null | tr -d '\r')
[ "$RIVO_CLEAR_RESULT" = Success ] || die 'Could not clear dev.rivo.relay; stopping before pairing.'
RIVO_APP_CLEARED=1

# Save first, so even a subsequent failed set/injection has a restoration record.
"$RIVO_PYTHON" "$RIVO_ROOT/scripts/emulator-camera-pose.py" --discovery "$RIVO_DISCOVERY" \
  --proto-dir "$RIVO_PROTO_DIR" --save "$RIVO_TEMP/original-pose.json" >/dev/null 2>&1 \
  || die 'Could not save camera pose through authenticated gRPC.'
"$RIVO_PYTHON" -m rivo_relay pair --workspace "$RIVO_WORKSPACE" --url "$RIVO_NODE_URL" \
  --ttl 300 --json-output "$RIVO_TEMP/pairing.json" >/dev/null 2>&1 || die 'Could not generate the one-time pairing file.'
"$RIVO_PYTHON" - "$RIVO_TEMP/pairing.json" "$RIVO_TEMP/camera-qr.png" <<'PY'
import json, sys
from pathlib import Path
import qrcode
data = json.loads(Path(sys.argv[1]).read_text())
qr = qrcode.QRCode(border=20, box_size=10)
qr.add_data(data['url'])
qr.make(fit=True)
qr.make_image(fill_color='black', back_color='white').convert('RGB').save(sys.argv[2])
PY
RIVO_QR_CACHE=$("$RIVO_PYTHON" - "$RIVO_TEMP/camera-qr.png" <<'PY'
import hashlib, os, sys, tempfile
from pathlib import Path
path = Path(sys.argv[1])
print(Path(tempfile.gettempdir()) / ('rivo-relay-qr-' + str(os.getuid())) / (hashlib.sha256(path.read_bytes()).hexdigest() + path.suffix))
PY
)
RIVO_POSTER_CHANGED=1
"$RIVO_PYTHON" "$RIVO_ROOT/scripts/qr-emulator-camera.py" --serial "$RIVO_SERIAL" inject \
  "$RIVO_TEMP/camera-qr.png" >/dev/null 2>&1 || die 'Could not load the camera QR poster.'
"$RIVO_PYTHON" "$RIVO_ROOT/scripts/emulator-camera-pose.py" --discovery "$RIVO_DISCOVERY" \
  --proto-dir "$RIVO_PROTO_DIR" --position -3.307 .7 .986 --rotation 5 -143 0 >/dev/null 2>&1 \
  || die 'Could not set the verified camera pose.'

run_test() {
  local method=$1
  printf 'Running LiveSmokeTest#%s…\n' "$method"
  "$RIVO_PYTHON" - "$RIVO_ADB" "$RIVO_SERIAL" "$RIVO_PROJECT_NAME" "$method" "$RIVO_OUT/logs/$method.log" <<'PY'
import re, shlex, subprocess, sys
from pathlib import Path
adb, serial, project, method, destination = sys.argv[1:]
command = ['am', 'instrument', '-w', '-r', '-e', 'class', 'dev.rivo.relay.LiveSmokeTest#' + method,
           '-e', 'projectName', project, 'dev.rivo.relay.test/androidx.test.runner.AndroidJUnitRunner']
try:
    result = subprocess.run([adb, '-s', serial, 'shell', shlex.join(command)],
                            capture_output=True, text=True, timeout=420)
    # Freeform test/stack/UI text can include credentials. Keep ONLY this fixed
    # status grammar; raw output remains in process memory and is never saved.
    lines = (result.stdout + '\n' + result.stderr).splitlines()
    patterns = [r'INSTRUMENTATION_STATUS: class=dev\.rivo\.relay\.LiveSmokeTest',
                r'INSTRUMENTATION_STATUS: test=' + re.escape(method),
                r'INSTRUMENTATION_STATUS: (?:current|numtests)=\d+',
                r'INSTRUMENTATION_STATUS_CODE: -?\d+', r'INSTRUMENTATION_CODE: -?\d+',
                r'Time: [0-9.]+', r'OK \(1 test\)', r'FAILURES!!!',
                r'Tests run: \d+,  Failures: \d+']
    safe = [line for line in lines if any(re.fullmatch(pattern, line) for pattern in patterns)]
    success = result.returncode == 0 and 'OK (1 test)' in safe and 'INSTRUMENTATION_CODE: -1' in safe
    safe += ['adb_exit_code=' + str(result.returncode), 'success=' + str(success).lower(),
             'freeform_diagnostics_retained=false']
except (subprocess.TimeoutExpired, KeyboardInterrupt):
    # Only this test App is stopped, so an abandoned instrumentation run cannot
    # move the scanner after the host restores its pose. Never stop the emulator.
    try:
        subprocess.run([adb, '-s', serial, 'shell', 'am force-stop dev.rivo.relay'],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        pass
    safe, success = ['success=false', 'error=instrumentation_timeout', 'freeform_diagnostics_retained=false'], False
Path(destination).write_text('\n'.join(safe) + '\n')
raise SystemExit(0 if success else 1)
PY
}

for method in qrCameraPairing nativeConversationRoundTrip todoCrudAndRealDispatch; do
  run_test "$method" || die "LiveSmokeTest#$method failed; stopping without retry. Sanitized status is in the output logs."
  RIVO_TESTS_PASSED=$((RIVO_TESTS_PASSED + 1))
  collect_evidence
done
for name in qr-paired-projects native-conversation todo-native-dispatch; do
  [ -s "$RIVO_OUT/screenshots/$name.png" ] || die "The expected $name screenshot was not available."
done
printf '%s\n' 'All three live tests passed; restoring the camera and removing pairing artifacts.'
