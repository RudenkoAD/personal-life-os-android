#!/usr/bin/env bash
set -euo pipefail
usage() { echo "usage: $0 [--publish] --artifact APK [--output DIR] [--host HOST] [--user USER] [--ssh-key PATH] [--known-hosts PATH]" >&2; exit 2; }
publish=0; artifact=; output=; host=${UPDATE_HOST:-}; user=${UPDATE_USER:-}; key=${UPDATE_SSH_KEY:-}; known_hosts=${UPDATE_KNOWN_HOSTS:-}
while (($#)); do
  case "$1" in
    --publish) publish=1; shift;;
    --artifact) (($# >= 2)) || usage; artifact=$2; shift 2;;
    --output) (($# >= 2)) || usage; output=$2; shift 2;;
    --host) (($# >= 2)) || usage; host=$2; shift 2;;
    --user) (($# >= 2)) || usage; user=$2; shift 2;;
    --ssh-key) (($# >= 2)) || usage; key=$2; shift 2;;
    --known-hosts) (($# >= 2)) || usage; known_hosts=$2; shift 2;;
    *) usage;;
  esac
done
[[ -n "$artifact" ]] || usage
script_dir=$(cd -- "$(dirname -- "$0")" && pwd); repo_dir=$(cd -- "$script_dir/.." && pwd)
[[ -n "$output" ]] || output="$repo_dir/outputs/android-update"
if [[ "$artifact" = /* ]]; then artifact_path=$artifact; else artifact_path="$repo_dir/$artifact"; fi
python3 "$script_dir/prepare-update.py" "$artifact_path" --output "$output"
version_code=$(python3 - "$output/latest.json" <<'PY'
import json, re, sys
from pathlib import Path
p=json.loads(Path(sys.argv[1]).read_text())
if p.get('schemaVersion') != 1 or not isinstance(p.get('versionCode'), int) or not re.fullmatch(r'[0-9a-f]{64}', p.get('sha256','')): raise SystemExit('invalid prepared metadata')
print(p['versionCode'])
PY
)
if ((publish == 0)); then echo "prepared update versionCode=$version_code at $output"; exit 0; fi
[[ -n "$host" && -n "$user" ]] || { echo "--publish requires --host and --user" >&2; exit 2; }
ssh_opts=(-o StrictHostKeyChecking=yes); [[ -n "$key" ]] && ssh_opts+=(-i "$key"); [[ -n "$known_hosts" ]] && ssh_opts+=(-o "UserKnownHostsFile=$known_hosts")
remote="$user@$host"
remote_tmp=$(ssh "${ssh_opts[@]}" "$remote" 'mktemp -d /tmp/life-os-android-update.XXXXXX')
cleanup() { ssh "${ssh_opts[@]}" "$remote" "rm -rf -- $(printf '%q' "$remote_tmp")" >/dev/null 2>&1 || true; }; trap cleanup EXIT
scp "${ssh_opts[@]}" -r "$output/." "$remote:$remote_tmp/"
ssh "${ssh_opts[@]}" "$remote" "UPDATE_TMP=$(printf '%q' "$remote_tmp") sh -s" <<'REMOTE'
set -eu
UPDATE_ROOT=/srv/personal-life-os/android
mkdir -p "$UPDATE_ROOT/releases" "$UPDATE_ROOT/.staging"
exec 9>"$UPDATE_ROOT/.publish.lock"; flock -x 9
python3 - "$UPDATE_TMP/latest.json" "$UPDATE_ROOT/latest.json" "$UPDATE_TMP" "$UPDATE_ROOT" <<'PY'
import hashlib, json, os, shutil, sys
from pathlib import Path
meta_path, old_path, incoming_root, root = map(Path, sys.argv[1:]); new=json.loads(meta_path.read_text())
if new.get('schemaVersion') != 1 or not isinstance(new.get('versionCode'), int): raise SystemExit('invalid metadata')
vc=str(new['versionCode']); expected=Path(new['apkPath']).name
if new['apkPath'] != f'/android/releases/{vc}/{expected}': raise SystemExit('unexpected apkPath')
source=incoming_root/'releases'/vc; incoming=source/expected
if not incoming.is_file() or any(p.suffix=='.apk' and p.name != expected for p in source.iterdir()): raise SystemExit('incomplete or unexpected incoming release')
if incoming.stat().st_size != new['sizeBytes'] or hashlib.sha256(incoming.read_bytes()).hexdigest() != new['sha256']: raise SystemExit('incoming APK verification failed')
if old_path.exists():
 old=json.loads(old_path.read_text())
 if int(new['versionCode']) < int(old['versionCode']): raise SystemExit('refusing to publish older versionCode')
 if int(new['versionCode']) == int(old['versionCode']) and new['sha256'] != old['sha256']: raise SystemExit('versionCode digest mismatch')
release=root/'releases'/vc
if release.exists():
 existing=release/expected
 if not existing.is_file() or existing.stat().st_size != new['sizeBytes'] or hashlib.sha256(existing.read_bytes()).hexdigest() != new['sha256']: raise SystemExit('refusing to replace incomplete or different immutable APK')
else:
 staged=root/'.staging'/(vc+'.'+new['sha256'][:12]); shutil.rmtree(staged, ignore_errors=True); shutil.copytree(source, staged)
 staged.chmod(0o755); (staged/expected).chmod(0o644); os.replace(staged, release)
tmp=root/'.staging'/'.latest.json.tmp'; shutil.copy2(meta_path,tmp); tmp.chmod(0o644); os.replace(tmp,old_path)
PY
rm -rf -- "$UPDATE_TMP"
REMOTE
trap - EXIT; echo "published versionCode=$version_code"
