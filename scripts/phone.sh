#!/bin/bash
# Drives the test phone one step at a time for the device checks in Roadmap_Blocked.md.
#
# Every tap is refused unless TikTok is in the foreground, because a phone in somebody's hand
# takes the foreground between two adb calls and a queued tap then lands in another app. Every
# step can be followed by a screenshot, which is the evidence a device check produces.
#
# Coordinates are given in the space of a screenshot viewed at 933 px wide (what an image
# viewer shows for a 1080 px panel) and scaled here, so numbers read off the picture can be
# used as they are. Set PHONE_SCALE=1 to give panel pixels instead.
#
#   scripts/phone.sh shot 01-feed                 screenshot to $PHONE_SHOTS/01-feed.png
#   scripts/phone.sh tap 464 393                  tap, then wait 2 s
#   scripts/phone.sh swipe 466 1500 466 500       a scroll up
#   scripts/phone.sh key KEYCODE_BACK
#   scripts/phone.sh text hello
#   scripts/phone.sh open_url https://www.tiktok.com/@creator/video/123
#   scripts/phone.sh top                          the foreground activity
#   scripts/phone.sh logcat "morphe|hushfeed"     recent payload lines
#   scripts/phone.sh gfx                          frame stats for the perf device check
#
# PHONE_SERIAL selects the device; it must be the test phone, never the owner's own.
# HUSHFEED_DEVICE_SERIAL names the one test phone this machine may drive, and PHONE_SERIAL has
# to equal it, so a typo or another phone plugged in beside it is refused.
#
# The phones are shared, so every adb call also needs the device lease scripts/device-lease.ps1
# took for that phone: HUSHFEED_LEASE_FILE is its file and HUSHFEED_LEASE_TOKEN its ownership
# token. The file is read again before each call, and a missing file, another serial, another
# token or a passed expiry refuses the call before adb runs.
set -eu
S="${PHONE_SERIAL:?set PHONE_SERIAL to the test phone serial}"
ALLOWED="${HUSHFEED_DEVICE_SERIAL:?set HUSHFEED_DEVICE_SERIAL to the serial of the test phone this machine may drive}"
if [[ "$S" != "$ALLOWED" ]]; then
    echo "REFUSED: device $S is not the test phone named by HUSHFEED_DEVICE_SERIAL" >&2
    exit 2
fi

normalise_path() {
    local candidate converted
    candidate="$1"
    if [[ "$candidate" =~ ^[[:alpha:]]:[\\/].* ]]; then
        if command -v wslpath >/dev/null 2>&1; then
            if converted=$(wslpath -u "$candidate" 2>/dev/null); then
                printf '%s\n' "$converted"
                return
            fi
            echo "normalise_path: wslpath could not convert $candidate" >&2
            return 1
        fi
    fi
    printf '%s\n' "$candidate"
}

find_adb() {
    local app_data candidate
    if command -v adb >/dev/null 2>&1; then
        command -v adb
        return
    fi
    if command -v adb.exe >/dev/null 2>&1; then
        command -v adb.exe
        return
    fi
    app_data=$(normalise_path "${LOCALAPPDATA:-}")
    [[ -n "$app_data" ]] || return 1
    for candidate in \
        "$app_data/Android/Sdk/platform-tools/adb.exe" \
        "$app_data"/Microsoft/WinGet/Packages/Google.PlatformTools*/platform-tools/adb.exe; do
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    return 1
}

if [[ -n "${ADB:-}" ]]; then
    ADB=$(normalise_path "$ADB")
elif ! ADB=$(find_adb); then
    echo "REFUSED: adb was not found" >&2
    exit 127
fi
SP=$(normalise_path "${PHONE_SHOTS:-${TEMP:-/tmp}/hushfeed-device/shots}")
PKG=com.zhiliaoapp.musically
SCALE="${PHONE_SCALE:-1.15756}"
mkdir -p "$SP"

refuse_lease() { echo "REFUSED: no device lease for $S ($1). No device command was sent." >&2; exit 2; }
# One string or number field of the lease's JSON, or nothing unless it appears exactly once.
lease_field() {
    local matches value
    matches=$(printf '%s\n' "$2" | grep -oE "\"$1\"[[:space:]]*:[[:space:]]*(\"[^\"]*\"|[0-9]+)") || return 1
    [[ "$matches" != *$'\n'* ]] || return 1
    value=${matches#*:}
    value=${value#"${value%%[![:space:]]*}"}
    value=${value#\"}
    printf '%s\n' "${value%\"}"
}
# Called inside a command substitution, the refusal ends that subshell with 2, and the caller's
# `|| return` or set -e carries the 2 out. Either way adb never runs.
require_lease() {
    local file text expires ends
    [[ -n "${HUSHFEED_LEASE_FILE:-}" && -n "${HUSHFEED_LEASE_TOKEN:-}" ]] ||
        refuse_lease "set HUSHFEED_LEASE_FILE and HUSHFEED_LEASE_TOKEN"
    file=$(normalise_path "$HUSHFEED_LEASE_FILE") || refuse_lease "its path could not be read"
    text=$(cat -- "$file" 2>/dev/null) || refuse_lease "$file could not be read"
    [[ "$(lease_field schemaVersion "$text")" == 1 ]] || refuse_lease "unknown lease format"
    [[ "$(lease_field serial "$text")" == "$S" ]] || refuse_lease "the lease names another phone"
    [[ "$(lease_field ownershipToken "$text")" == "$HUSHFEED_LEASE_TOKEN" ]] || refuse_lease "another task holds it"
    expires=$(lease_field expiresUtc "$text") || refuse_lease "it has no expiry"
    ends=$(date -u -d "$expires" +%s 2>/dev/null) || refuse_lease "its expiry is unreadable"
    (( ends > $(date -u +%s) )) || refuse_lease "it expired at $expires"
}
adb_call() { local seconds="$1"; shift; require_lease; timeout "$seconds" "$ADB" -s "$S" "$@"; }
# The same, with adb's stderr dropped. The refusal still reaches stderr.
adb_quiet() { local seconds="$1"; shift; require_lease; timeout "$seconds" "$ADB" -s "$S" "$@" 2>/dev/null; }

parse_top() {
    awk '
        /^WINDOW MANAGER / {
            default_display = 0
            displays_section = ($0 ~ /^WINDOW MANAGER DISPLAY CONTENTS \(/)
            next
        }
        displays_section && /^[[:space:]]*Display: mDisplayId=/ {
            default_display = ($0 ~ /^[[:space:]]*Display: mDisplayId=0([[:space:]]|$)/)
            next
        }
        displays_section && default_display && /^[[:space:]]*mCurrentFocus=/ {
            record = $0
            while (record !~ /}/ && record !~ /=null[[:space:]]*$/ && (getline continuation) > 0) {
                record = record " " continuation
            }
            sub(/^.*mCurrentFocus=Window\{[^[:space:]]+[[:space:]]+u[0-9]+[[:space:]]+/, "", record)
            sub(/}.*$/, "", record)
            sub(/^[[:space:]]+/, "", record)
            sub(/[[:space:]]+$/, "", record)
            if (record ~ /^[^[:space:]]+\/[^[:space:]]+$/) {
                print record
                found = 1
            }
            exit
        }
        END { if (!found) exit 1 }
    '
}
parse_popup_top() {
    # A panel title has no package. Require its real owner and application token to match.
    awk -v package="$PKG" '
        function normalise(value) { gsub(/[[:space:]]+/, " ", value); sub(/^ /, "", value); sub(/ $/, "", value); return value }
        function scalar(block, name, value) {
            if (!match(block, "(^| )" name "=[^ ]+")) return ""
            value = substr(block, RSTART, RLENGTH); sub(/^ ?[^=]+=/, "", value); return value
        }
        function activity(block, name, value) {
            if (!match(block, name "=ActivityRecord\\{[^}]+}")) return ""
            value = substr(block, RSTART, RLENGTH); sub(/^[^=]+=/, "", value); return normalise(value)
        }
        /^WINDOW MANAGER / {
            current = ""; default_display = 0
            windows_section = ($0 ~ /^WINDOW MANAGER WINDOWS \(/)
            displays_section = ($0 ~ /^WINDOW MANAGER DISPLAY CONTENTS \(/)
            next
        }
        windows_section && /^[[:space:]]*Window #[0-9]+ Window\{/ {
            current = $0; sub(/^.*Window\{/, "", current); sub(/[[:space:]].*$/, "", current)
            if (current in blocks) duplicate = 1
            blocks[current] = ""
        }
        windows_section && current != "" { blocks[current] = blocks[current] " " normalise($0) }
        displays_section && /^[[:space:]]*Display: mDisplayId=/ {
            default_display = ($0 ~ /^[[:space:]]*Display: mDisplayId=0([[:space:]]|$)/)
            next
        }
        displays_section && default_display && /^[[:space:]]*m(CurrentFocus|FocusedApp)=/ {
            record = $0
            while (record !~ /}/ && record !~ /=null[[:space:]]*$/ && (getline continuation) > 0) record = record " " continuation
            if (record ~ /mCurrentFocus=/) { focus = normalise(record); focus_count++ }
            else { app = activity(normalise(record), "mFocusedApp"); app_count++ }
        }
        END {
            if (duplicate || focus_count != 1 || app_count != 1 || app == "") exit 1
            if (focus !~ /mCurrentFocus=Window\{[^ ]+ u[0-9]+ PopupWindow:[[:xdigit:]]+}$/) exit 1
            id = focus; sub(/^.*Window\{/, "", id); sub(/ .*/, "", id)
            popup = blocks[id]
            if (scalar(popup, "mDisplayId") != "0" || scalar(popup, "package") != package ||
                    popup !~ /(^| )ty=APPLICATION_PANEL( |$)/ || activity(popup, "mActivityRecord") != app) exit 1
            uid = scalar(popup, "mOwnerUid")
            if (uid !~ /^[0-9]+$/) exit 1
            component = app; sub(/^ActivityRecord\{[^ ]+ u[0-9]+ /, "", component); sub(/ .*/, "", component)
            if (index(component, package "/") != 1 || component !~ /^[^ ]+\/[^ ]+$/) exit 1
            for (owner in blocks) {
                title = blocks[owner]; sub(/^ *Window #[0-9]+ Window\{[^ ]+ u[0-9]+ /, "", title); sub(/}.*/, "", title)
                if (title == component && scalar(blocks[owner], "mDisplayId") == "0" &&
                        scalar(blocks[owner], "package") == package && scalar(blocks[owner], "mOwnerUid") == uid &&
                        activity(blocks[owner], "mActivityRecord") == app) { print component; exit 0 }
            }
            exit 1
        }
    '
}
top() {
    local result
    result=$(adb_quiet 30 shell dumpsys window -a) || return $?
    if printf '%s\n' "$result" | parse_top; then return 0; fi
    printf '%s\n' "$result" | parse_popup_top
}
# Written to a temporary name and moved into place only after a zero exit: the redirection
# creates the file before adb runs, so a dropped device or a timeout left an empty .png where
# a device check expected evidence.
shot() { local activity tmp; tmp="$SP/$1.png.part"; if ! adb_quiet 60 exec-out screencap -p > "$tmp" || [ ! -s "$tmp" ]; then rm -f "$tmp"; echo "screencap failed for $1" >&2; return 1; fi; mv -f "$tmp" "$SP/$1.png"; activity=$(top) || return $?; echo "shot $SP/$1.png top=$activity"; }
guard() { local t; t=$(top); case "$t" in $PKG/*) ;; *) echo "REFUSED: foreground is $t"; exit 2;; esac; }
px() { awk "BEGIN{printf \"%d\", $1*$SCALE}"; }
tap() { guard; adb_call 30 shell input -d 0 tap "$(px "$1")" "$(px "$2")"; sleep "${3:-2}"; }
swipe() { guard; adb_call 30 shell input -d 0 swipe "$(px "$1")" "$(px "$2")" "$(px "$3")" "$(px "$4")" "${5:-300}"; sleep "${6:-2}"; }
key() { guard; adb_call 30 shell input -d 0 keyevent "$1"; sleep "${2:-2}"; }
text() { guard; adb_call 30 shell input -d 0 text "$1"; sleep 1; }
# adb hands its arguments to the phone's shell as one command line, so the URL is held to path
# characters: a ; & | $ quote or space would run there as a command instead of opening a page.
open_url() {
    local url="${1:?open_url requires a TikTok URL}" pattern='^https://www\.tiktok\.com/[A-Za-z0-9._~/@-]*$'
    if [[ ! "$url" =~ $pattern ]]; then
        echo "REFUSED: open_url only accepts https://www.tiktok.com/ URLs made of path characters" >&2
        return 2
    fi
    guard
    adb_call 30 shell am start -W -a android.intent.action.VIEW -d "$url" -p "$PKG" >/dev/null
    sleep "${2:-3}"
}
logcat() { local result pattern; pattern="${1:?logcat requires a filter}"; result=$(adb_quiet 60 logcat -d) || return $?; printf '%s\n' "$result" | grep -iE "$pattern" | tail -"${2:-20}"; }
gfx() { local result; result=$(adb_quiet 60 shell dumpsys gfxinfo $PKG "${1:-}") || return $?; printf '%s\n' "$result" | grep -E "Total frames|Janky|50th|90th|99th|Number Frame|Uptime" | head -12; }
"$@"
