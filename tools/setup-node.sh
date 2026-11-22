#!/usr/bin/env bash
# Mobile ASL Client — node setup
#
# Creates (or re-creates) a dedicated IAX login on your AllStarLink node so the
# Mobile ASL Client app can connect to it, and prints a QR code / link that adds
# the node to the app in one tap.
#
# Works on native ASL installs and on ASL running inside a Docker container.
#
#   curl -fsSL https://raw.githubusercontent.com/pyopower/allstar-mobile/main/tools/setup-node.sh | sudo bash
#
# Options:  --node N         node number (default: ask / the only local node)
#           --container C    Docker container name (default: auto-detect)
#           --host H         address the phone will use (default: public IP)
#           --remove         remove the login again
#
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

USER_NAME="${MASL_USER:-allstarmobile}"
NODE=""; CONTAINER=""; HOST=""; REMOVE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --node) NODE="$2"; shift 2 ;;
    --container) CONTAINER="$2"; shift 2 ;;
    --host) HOST="$2"; shift 2 ;;
    --remove) REMOVE=1; shift ;;
    -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

b=$(tput bold 2>/dev/null || true); n=$(tput sgr0 2>/dev/null || true)
say()  { echo "${b}==>${n} $*"; }
warn() { echo "${b}[!]${n} $*" >&2; }
die()  { echo "${b}[x]${n} $*" >&2; exit 1; }
ask()  { local a; if [ -r /dev/tty ]; then read -r -p "$1" a </dev/tty; else a=""; fi; echo "$a"; }

[ "$(id -u)" -eq 0 ] || die "Please run as root (sudo)."

# ---------------------------------------------------------------- find Asterisk
MODE=""
if [ -z "$CONTAINER" ] && command -v asterisk >/dev/null 2>&1 && asterisk -rx "core show version" >/dev/null 2>&1; then
  MODE=native
elif command -v docker >/dev/null 2>&1; then
  if [ -z "$CONTAINER" ]; then
    found=()
    for c in $(docker ps --format '{{.Names}}'); do
      if docker exec "$c" sh -c 'asterisk -rx "core show version"' >/dev/null 2>&1; then found+=("$c"); fi
    done
    [ ${#found[@]} -gt 0 ] || die "No running Asterisk found (native or in Docker)."
    if [ ${#found[@]} -gt 1 ]; then
      echo "Asterisk containers: ${found[*]}"
      CONTAINER=$(ask "Which one? [${found[0]}] "); CONTAINER=${CONTAINER:-${found[0]}}
    else
      CONTAINER=${found[0]}
    fi
  fi
  docker exec "$CONTAINER" sh -c 'asterisk -rx "core show version"' >/dev/null 2>&1 \
    || die "Asterisk is not answering inside container '$CONTAINER'."
  MODE=docker
else
  die "Asterisk is not running here."
fi

run() { if [ "$MODE" = native ]; then sh -c "$1"; else docker exec -i "$CONTAINER" sh -c "$1"; fi; }
ax()  { run "asterisk -rx \"$1\""; }

say "Asterisk found ($MODE${CONTAINER:+: $CONTAINER}): $(ax 'core show version' | head -1 | cut -c1-60)"

CONF=/etc/asterisk
run "test -w $CONF/iax.conf && test -w $CONF/extensions.conf" || die "Cannot write $CONF/iax.conf or extensions.conf."

# ---------------------------------------------------------------- remove old block
# Deletes every [allstarmobile] section (up to the next [section]) from a file.
strip_block() {
  run "f=$1; cp \"\$f\" \"\$f.masl-bak\"; awk '
    /^[[:space:]]*\\[/ { skip = (\$0 ~ /^[[:space:]]*\\[$USER_NAME\\]/) }
    !skip' \"\$f.masl-bak\" > \"\$f.masl-tmp\" && cat \"\$f.masl-tmp\" > \"\$f\" && rm -f \"\$f.masl-tmp\""
}

strip_block $CONF/iax.conf
strip_block $CONF/extensions.conf

if [ $REMOVE -eq 1 ]; then
  ax "iax2 reload" >/dev/null; ax "dialplan reload" >/dev/null
  say "Removed the '$USER_NAME' login. Backups: $CONF/*.masl-bak"
  exit 0
fi

# ---------------------------------------------------------------- node number
mapfile -t NODES < <(ax "rpt localnodes" 2>/dev/null | tr -d '\r' | grep -E '^[[:space:]]*[0-9]{3,7}[[:space:]]*$' | tr -d ' ' || true)
if [ -z "$NODE" ]; then
  if [ ${#NODES[@]} -eq 1 ]; then NODE=${NODES[0]}
  elif [ ${#NODES[@]} -gt 1 ]; then
    echo "Local nodes: ${NODES[*]}"
    NODE=$(ask "Node for the app? [${NODES[0]}] "); NODE=${NODE:-${NODES[0]}}
  else
    NODE=$(ask "Your node number: ")
  fi
fi
[[ "$NODE" =~ ^[0-9]{3,7}$ ]] || die "Invalid node number '$NODE'."
[ ${#NODES[@]} -eq 0 ] || printf '%s\n' "${NODES[@]}" | grep -qx "$NODE" || warn "Node $NODE is not in 'rpt localnodes' (${NODES[*]})."

# ---------------------------------------------------------------- write config
SECRET=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24 || true)
[ ${#SECRET} -eq 24 ] || die "Could not generate a password."

run "cat >> $CONF/iax.conf" <<EOF

[$USER_NAME]                    ; Mobile ASL Client (added by setup-node.sh)
type = user
context = $USER_NAME
auth = md5
secret = $SECRET
host = dynamic
disallow = all
allow = ulaw
allow = gsm
transfer = no
EOF

{
  echo ""
  echo "[$USER_NAME]                    ; Mobile ASL Client (added by setup-node.sh)"
  for x in $(printf '%s\n' "$NODE" "${NODES[@]}" | sort -u); do
    echo "exten => $x,1,rpt(\${EXTEN}|X)"
  done
} | run "cat >> $CONF/extensions.conf"

ax "iax2 reload" >/dev/null
ax "dialplan reload" >/dev/null
ax "iax2 show users" | grep -q "^$USER_NAME " || die "Asterisk did not load the new user — check $CONF/iax.conf (backup: iax.conf.masl-bak)."
say "Login '$USER_NAME' created for node $NODE."

# ---------------------------------------------------------------- address
PORT=$(run "awk -F= '/^\\[general\\]/{g=1;next} /^\\[/{g=0} g&&\$1~/^[[:space:]]*bindport[[:space:]]*\$/{gsub(/[ \\t;].*/,\"\",\$2); print \$2; exit}' $CONF/iax.conf" 2>/dev/null | tr -dc 0-9 || true)
PORT=${PORT:-4569}
LAN=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
if [ -z "$HOST" ]; then
  HOST=$(curl -fsS -4 --max-time 5 https://api.ipify.org 2>/dev/null || true)
  [ -n "$HOST" ] || HOST=$LAN
fi

if [ "$MODE" = docker ]; then
  if ! docker inspect -f '{{range .Mounts}}{{println .Destination}}{{end}}' "$CONTAINER" | grep -qE '^/etc/asterisk/?$'; then
    warn "/etc/asterisk is not on a Docker volume: if the container is re-created (update),"
    warn "the login is lost. Just run this script again afterwards."
  fi
fi

LINK="mobileasl://add?host=$HOST&port=$PORT&user=$USER_NAME&secret=$SECRET&node=$NODE"

echo
echo "  Host      $HOST${LAN:+   (at home: $LAN)}"
echo "  Port      $PORT (UDP)"
echo "  User      $USER_NAME"
echo "  Password  $SECRET"
echo "  Node      $NODE"
echo
if command -v qrencode >/dev/null 2>&1; then
  say "Scan this with the phone camera (or in the app: Settings → Set up my node):"
  qrencode -t ANSIUTF8 -m 2 "$LINK"
else
  say "Open this link on the phone, or paste it in the app (Settings → Set up my node):"
  echo "  (install 'qrencode' to get a QR code here)"
fi
echo "  $LINK"
echo
say "Remember: UDP $PORT must reach this node from the Internet (router port forward)."
