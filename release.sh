#!/usr/bin/env bash
# Polar Release Script - 打包并推送到 Git 仓库
# Usage: bash release.sh

set -e

REPO_URL="https://github.com/hbsjz-swl/dlc-cli.git"
RELEASE_DIR="/tmp/dlc-cli"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo ""
echo "  [1/3] Building jar..."
cd "$SCRIPT_DIR"
mvn package -DskipTests -q
echo "  Done. $(ls -lh target/dlc-*.jar | awk '{print $5, $NF}')"

echo ""
echo "  [2/3] Updating release repo..."

# Clone or pull existing repo
if [ -d "$RELEASE_DIR/.git" ]; then
    cd "$RELEASE_DIR"
    git pull --rebase origin main -q 2>/dev/null || true
else
    rm -rf "$RELEASE_DIR"
    git clone "$REPO_URL" "$RELEASE_DIR" -q 2>/dev/null || {
        mkdir -p "$RELEASE_DIR"
        cd "$RELEASE_DIR"
        git init -q
        git remote add origin "$REPO_URL"
    }
    cd "$RELEASE_DIR"
fi

# Copy latest files
cp "$SCRIPT_DIR/target/dlc-"*"-SNAPSHOT.jar" "$RELEASE_DIR/dlc.jar"
cp "$SCRIPT_DIR/install.sh" "$RELEASE_DIR/"
cp "$SCRIPT_DIR/install.bat" "$RELEASE_DIR/"
mkdir -p "$RELEASE_DIR/bin"
cp "$SCRIPT_DIR/bin/polar" "$RELEASE_DIR/bin/"
cp "$SCRIPT_DIR/bin/polar.cmd" "$RELEASE_DIR/bin/"
cp "$SCRIPT_DIR/bin/dlc" "$RELEASE_DIR/bin/"
cp "$SCRIPT_DIR/bin/dlc.cmd" "$RELEASE_DIR/bin/"

# Always update README
cp "$SCRIPT_DIR/README.md" "$RELEASE_DIR/"

echo ""
echo "  [3/3] Pushing to remote..."
cd "$RELEASE_DIR"
git add -A
if git diff --cached --quiet; then
    echo "  No changes to push."
else
    git commit -m "release: update Polar $(date '+%Y-%m-%d %H:%M')"
    git push -u origin main
    echo ""
    echo "  Released successfully!"
fi

echo ""
