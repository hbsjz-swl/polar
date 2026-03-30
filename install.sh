#!/usr/bin/env bash
# Polar Installer
# 蒂爱嘉(北京)有限公司
#
# Usage:
#   git clone https://github.com/hbsjz-swl/dlc-cli.git && cd dlc-cli && bash install.sh

set -e

APP_HOME="${DLC_HOME:-$HOME/.dlc}"

echo ""
echo "  Installing Polar - Local AI Coding Agent ..."
echo "  Install dir: $APP_HOME"
echo ""

# Create install directory
mkdir -p "$APP_HOME/bin"

# Determine source: local file or git clone
if [ -f "dlc.jar" ]; then
    echo "  Copying dlc.jar from local directory..."
    cp "dlc.jar" "$APP_HOME/dlc.jar"
else
    echo "  Cloning repository to download dlc.jar..."
    TEMP_DIR=$(mktemp -d)
    git clone --depth 1 "https://github.com/hbsjz-swl/dlc-cli.git" "$TEMP_DIR" -q
    if [ -f "$TEMP_DIR/dlc.jar" ]; then
        cp "$TEMP_DIR/dlc.jar" "$APP_HOME/dlc.jar"
    else
        echo "  Error: dlc.jar not found in repository."
        rm -rf "$TEMP_DIR"
        exit 1
    fi
    rm -rf "$TEMP_DIR"
fi

echo "  jar size: $(du -h "$APP_HOME/dlc.jar" | cut -f1)"

cp "bin/polar" "$APP_HOME/bin/polar"
chmod +x "$APP_HOME/bin/polar"
cat > "$APP_HOME/bin/dlc" << 'LAUNCHER'
#!/usr/bin/env bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/polar" "$@"
LAUNCHER
chmod +x "$APP_HOME/bin/dlc"

# Add to PATH
SHELL_NAME="$(basename "$SHELL")"
case "$SHELL_NAME" in
    zsh)  PROFILE="$HOME/.zshrc" ;;
    bash) PROFILE="$HOME/.bashrc"
          [ -f "$HOME/.bash_profile" ] && PROFILE="$HOME/.bash_profile" ;;
    *)    PROFILE="$HOME/.profile" ;;
esac

PATH_LINE='export PATH="$HOME/.dlc/bin:$PATH"'

if ! grep -qF '.dlc/bin' "$PROFILE" 2>/dev/null; then
    echo "" >> "$PROFILE"
    echo "# DLC - Local AI Coding Agent" >> "$PROFILE"
    echo "$PATH_LINE" >> "$PROFILE"
    echo "  Added PATH to $PROFILE"
fi

echo ""
echo "  Polar installed successfully!"
echo ""
echo "  Restart your terminal or run:"
echo "    source $PROFILE"
echo ""
echo "  Then use Polar in any project directory:"
echo "    cd /your/project"
echo "    polar"
echo ""
echo "  Update to latest version:"
echo "    polar upgrade"
echo ""
