#!/usr/bin/env bash
# DLC Installer
# 蒂爱嘉(北京)有限公司
#
# Usage:
#   git clone https://github.com/hbsjz-swl/dlc-cli.git && cd dlc-cli && bash install.sh

set -e

DLC_HOME="${DLC_HOME:-$HOME/.dlc}"

echo ""
echo "  Installing DLC - Local AI Coding Agent ..."
echo "  Install dir: $DLC_HOME"
echo ""

# Create install directory
mkdir -p "$DLC_HOME/bin"

# Determine source: local file or git clone
if [ -f "dlc.jar" ]; then
    echo "  Copying dlc.jar from local directory..."
    cp "dlc.jar" "$DLC_HOME/dlc.jar"
else
    echo "  Cloning repository to download dlc.jar..."
    TEMP_DIR=$(mktemp -d)
    git clone --depth 1 "https://github.com/hbsjz-swl/dlc-cli.git" "$TEMP_DIR" -q
    if [ -f "$TEMP_DIR/dlc.jar" ]; then
        cp "$TEMP_DIR/dlc.jar" "$DLC_HOME/dlc.jar"
    else
        echo "  Error: dlc.jar not found in repository."
        rm -rf "$TEMP_DIR"
        exit 1
    fi
    rm -rf "$TEMP_DIR"
fi

echo "  jar size: $(du -h "$DLC_HOME/dlc.jar" | cut -f1)"

# Create launcher script (with upgrade support)
cat > "$DLC_HOME/bin/dlc" << 'LAUNCHER'
#!/usr/bin/env bash
DLC_HOME="${DLC_HOME:-$HOME/.dlc}"
DLC_JAR="$DLC_HOME/dlc.jar"

if [ "${1:-}" = "upgrade" ]; then
    echo "  Upgrading DLC..."
    TEMP_DIR=$(mktemp -d)
    git clone --depth 1 "https://github.com/hbsjz-swl/dlc-cli.git" "$TEMP_DIR" -q 2>/dev/null
    if [ -f "$TEMP_DIR/dlc.jar" ]; then
        cp "$TEMP_DIR/dlc.jar" "$DLC_JAR"
        echo "  Done. $(du -h "$DLC_JAR" | cut -f1)"
    else
        echo "  Error: Failed to download."
    fi
    rm -rf "$TEMP_DIR"
    exit 0
fi

if [ ! -f "$DLC_JAR" ]; then
    echo "Error: dlc.jar not found at $DLC_JAR"
    echo "Run: git clone https://github.com/hbsjz-swl/dlc-cli.git && cd dlc-cli && bash install.sh"
    exit 1
fi

export DLC_WORKSPACE="${DLC_WORKSPACE:-$(pwd)}"
exec java -jar "$DLC_JAR" "$@"
LAUNCHER
chmod +x "$DLC_HOME/bin/dlc"

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
echo "  DLC installed successfully!"
echo ""
echo "  Restart your terminal or run:"
echo "    source $PROFILE"
echo ""
echo "  Then use DLC in any project directory:"
echo "    cd /your/project"
echo "    dlc"
echo ""
echo "  Update to latest version:"
echo "    dlc upgrade"
echo ""
