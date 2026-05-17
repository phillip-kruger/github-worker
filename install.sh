#!/usr/bin/env bash
set -euo pipefail

CONFIG_DIR="$HOME/.config/github-worker"
CONFIG_FILE="$CONFIG_DIR/config"
STATE_FILE="$CONFIG_DIR/state.json"
SCRIPT_NAME="github-worker"
INSTALL_DIR="$HOME/.local/share/github-worker"
BIN_DIR="$HOME/.local/bin"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== GitHub Worker — Setup ==="
echo

# --- Prerequisites ---

missing=()
command -v java &>/dev/null || missing+=("java")
command -v jbang &>/dev/null || missing+=("jbang")
command -v gh &>/dev/null || missing+=("gh (GitHub CLI)")
command -v claude &>/dev/null || missing+=("claude (Claude Code CLI)")

if [[ ${#missing[@]} -gt 0 ]]; then
    echo "Missing prerequisites: ${missing[*]}"
    echo "Please install them and re-run this script."
    exit 1
fi

if ! gh auth status &>/dev/null; then
    echo "GitHub CLI is not authenticated. Run 'gh auth login' first."
    exit 1
fi

echo "Prerequisites OK."
echo

# --- Gather config ---

default_gh_user=$(gh api user --jq '.login' 2>/dev/null || echo "")

read -rp "Your GitHub username [$default_gh_user]: " github_user
github_user="${github_user:-$default_gh_user}"

echo
echo "  Your GitHub PAT needs these scopes: repo, read:org"
echo "  Create one at: https://github.com/settings/tokens/new?scopes=repo,read:org&description=github-worker"
echo
read -srp "Your GitHub Personal Access Token: " github_token
echo

echo
read -rp "Bot GitHub username: " bot_user

echo
echo "  The bot PAT needs these scopes: repo, read:org, write:discussion"
echo "  Log in as the bot account and create one at:"
echo "  https://github.com/settings/tokens/new?scopes=repo,read:org,write:discussion&description=github-worker-bot"
echo
read -srp "Bot GitHub Personal Access Token: " bot_token
echo

read -rp "Gmail address: " gmail_address
echo
echo "  Gmail App Passwords work with 2FA-enabled accounts."
echo "  Create one at: https://myaccount.google.com/apppasswords"
echo "  Select 'Other' and name it 'github-worker'."
echo
read -srp "Gmail App Password: " gmail_app_password
echo
read -rp "Send email to (comma-separated addresses) [$gmail_address]: " send_to
send_to="${send_to:-$gmail_address}"
read -rp "GitHub orgs to exclude (comma-separated, or leave empty): " exclude_orgs
read -rp "Active hours (HH-HH, 24h format) [07-22]: " active_hours
active_hours="${active_hours:-07-22}"
read -rp "Work directory for cloned repos [/tmp/github-worker]: " work_dir
work_dir="${work_dir:-/tmp/github-worker}"
read -rp "Lookback days [7]: " lookback_days
lookback_days="${lookback_days:-7}"
read -rp "Schedule (cron format, e.g. '0 * * * *' for hourly) [0 * * * *]: " schedule
schedule="${schedule:-0 * * * *}"

echo

# --- Write config ---

mkdir -p "$CONFIG_DIR"
cat > "$CONFIG_FILE" <<EOF
GITHUB_USER=$github_user
GITHUB_TOKEN=$github_token
BOT_USER=$bot_user
BOT_TOKEN=$bot_token
EXCLUDE_ORGS=$exclude_orgs
GMAIL_ADDRESS=$gmail_address
GMAIL_APP_PASSWORD=$gmail_app_password
SEND_TO=$send_to
ACTIVE_HOURS=$active_hours
WORK_DIR=$work_dir
LOOKBACK_DAYS=$lookback_days
SCHEDULE=$schedule
EOF
chmod 600 "$CONFIG_FILE"
echo "Config written to $CONFIG_FILE"

# --- Initialize state ---

if [[ ! -f "$STATE_FILE" ]]; then
    echo '{"issues":{},"reviews":{}}' > "$STATE_FILE"
    echo "State file initialized at $STATE_FILE"
fi

# --- Install Java sources ---

mkdir -p "$INSTALL_DIR"
cp "$SCRIPT_DIR"/*.java "$INSTALL_DIR/"
echo "Java sources installed to $INSTALL_DIR"

# --- Create wrapper script ---

mkdir -p "$BIN_DIR"

# Detect SDKMAN paths for JBang and Java
JBANG_BIN=$(dirname "$(command -v jbang)" 2>/dev/null || echo "")
JAVA_BIN=$(dirname "$(command -v java)" 2>/dev/null || echo "")

cat > "$BIN_DIR/$SCRIPT_NAME" <<WRAPPER
#!/usr/bin/env bash
export PATH="$JBANG_BIN:$JAVA_BIN:$BIN_DIR:\$PATH"
exec jbang "$INSTALL_DIR/GitHubWorker.java" "\$@"
WRAPPER
chmod +x "$BIN_DIR/$SCRIPT_NAME"
echo "Wrapper script installed to $BIN_DIR/$SCRIPT_NAME"

# --- Detect OS and install scheduler ---

install_systemd() {
    local systemd_dir="$HOME/.config/systemd/user"
    mkdir -p "$systemd_dir"

    # Convert simple cron to OnCalendar
    # Handles: "0 * * * *" (hourly), "*/30 * * * *" (every 30 min),
    #          "0 8 * * *" (daily at 8), "0 8 * * 1-5" (weekdays at 8)
    local on_calendar
    on_calendar=$(cron_to_oncalendar "$schedule")

    cat > "$systemd_dir/$SCRIPT_NAME.service" <<EOF
[Unit]
Description=GitHub issue worker and PR reviewer

[Service]
Type=oneshot
ExecStart=$BIN_DIR/$SCRIPT_NAME
Environment=HOME=$HOME
Environment=PATH=$JBANG_BIN:$JAVA_BIN:$BIN_DIR:/usr/bin:/bin

[Install]
WantedBy=default.target
EOF

    cat > "$systemd_dir/$SCRIPT_NAME.timer" <<EOF
[Unit]
Description=Run GitHub worker on schedule

[Timer]
OnCalendar=$on_calendar
Persistent=true

[Install]
WantedBy=timers.target
EOF

    systemctl --user daemon-reload
    systemctl --user enable --now "$SCRIPT_NAME.timer"

    if command -v loginctl &>/dev/null; then
        loginctl enable-linger "$USER" 2>/dev/null || true
    fi

    echo "Systemd timer enabled (schedule: $on_calendar)."
}

install_launchd() {
    local plist_dir="$HOME/Library/LaunchAgents"
    local plist_file="$plist_dir/com.github.worker.plist"
    mkdir -p "$plist_dir"

    # Parse minute and hour from cron
    local cron_min cron_hour
    cron_min=$(echo "$schedule" | awk '{print $1}')
    cron_hour=$(echo "$schedule" | awk '{print $2}')

    # Build calendar interval entries
    local interval_entries=""
    if [[ "$cron_min" == "0" ]]; then
        interval_entries="<key>Minute</key><integer>0</integer>"
    elif [[ "$cron_min" =~ ^\*/([0-9]+)$ ]]; then
        # Every N minutes — launchd doesn't support */N directly, use multiple intervals
        local step="${BASH_REMATCH[1]}"
        interval_entries=""
        for (( m=0; m<60; m+=step )); do
            interval_entries+="
        <dict>
            <key>Minute</key>
            <integer>$m</integer>
        </dict>"
        done
    elif [[ "$cron_min" =~ ^[0-9]+$ ]]; then
        interval_entries="<key>Minute</key><integer>$cron_min</integer>"
    fi

    if [[ "$cron_hour" != "*" && "$cron_hour" =~ ^[0-9]+$ ]]; then
        interval_entries+="
            <key>Hour</key>
            <integer>$cron_hour</integer>"
    fi

    cat > "$plist_file" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.github.worker</string>
    <key>ProgramArguments</key>
    <array>
        <string>$BIN_DIR/$SCRIPT_NAME</string>
    </array>
    <key>StartCalendarInterval</key>
    <dict>
        $interval_entries
    </dict>
    <key>StandardOutPath</key>
    <string>$CONFIG_DIR/stdout.log</string>
    <key>StandardErrorPath</key>
    <string>$CONFIG_DIR/stderr.log</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>PATH</key>
        <string>$JBANG_BIN:$JAVA_BIN:$BIN_DIR:/usr/local/bin:/usr/bin:/bin</string>
        <key>HOME</key>
        <string>$HOME</string>
    </dict>
</dict>
</plist>
EOF

    launchctl unload "$plist_file" 2>/dev/null || true
    launchctl load "$plist_file"

    echo "Launchd agent installed (plist: $plist_file)."
}

cron_to_oncalendar() {
    local cron="$1"
    local min hour dom mon dow
    read -r min hour dom mon dow <<< "$cron"

    # Convert day-of-week: cron 0-6 (Sun-Sat) -> systemd Mon,Tue,...
    local sys_dow="*"
    if [[ "$dow" != "*" ]]; then
        sys_dow=$(echo "$dow" | sed \
            -e 's/0/Sun/g' -e 's/1/Mon/g' -e 's/2/Tue/g' -e 's/3/Wed/g' \
            -e 's/4/Thu/g' -e 's/5/Fri/g' -e 's/6/Sat/g' -e 's/7/Sun/g')
    fi

    # Convert minute
    local sys_min="*"
    if [[ "$min" == "0" ]]; then
        sys_min="00"
    elif [[ "$min" =~ ^\*/([0-9]+)$ ]]; then
        local step="${BASH_REMATCH[1]}"
        sys_min=""
        for (( m=0; m<60; m+=step )); do
            [[ -n "$sys_min" ]] && sys_min+=","
            sys_min+=$(printf "%02d" "$m")
        done
    elif [[ "$min" =~ ^[0-9]+$ ]]; then
        sys_min=$(printf "%02d" "$min")
    fi

    # Convert hour
    local sys_hour="*"
    if [[ "$hour" =~ ^[0-9]+$ ]]; then
        sys_hour=$(printf "%02d" "$hour")
    elif [[ "$hour" =~ ^\*/([0-9]+)$ ]]; then
        local step="${BASH_REMATCH[1]}"
        sys_hour=""
        for (( h=0; h<24; h+=step )); do
            [[ -n "$sys_hour" ]] && sys_hour+=","
            sys_hour+=$(printf "%02d" "$h")
        done
    fi

    # Convert month
    local sys_mon="*"
    [[ "$mon" =~ ^[0-9]+$ ]] && sys_mon=$(printf "%02d" "$mon")

    # Convert day-of-month
    local sys_dom="*"
    [[ "$dom" =~ ^[0-9]+$ ]] && sys_dom=$(printf "%02d" "$dom")

    if [[ "$sys_dow" != "*" ]]; then
        echo "$sys_dow *-$sys_mon-$sys_dom $sys_hour:$sys_min:00"
    else
        echo "*-$sys_mon-$sys_dom $sys_hour:$sys_min:00"
    fi
}

case "$(uname -s)" in
    Linux)
        install_systemd
        ;;
    Darwin)
        install_launchd
        ;;
    *)
        echo "Unsupported OS: $(uname -s)"
        echo "Please set up a scheduler manually to run: $BIN_DIR/$SCRIPT_NAME"
        ;;
esac

echo
echo "=== Setup complete! ==="
echo

# --- Offer test ---

read -rp "Run a preview test now? [Y/n] " run_test
if [[ "${run_test,,}" != "n" ]]; then
    "$BIN_DIR/$SCRIPT_NAME" --preview
fi
