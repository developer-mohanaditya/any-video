#!/usr/bin/env bash
# Installs the REA ("Reverse Engineer Anything") CLI from morluto/rea.
# Global npm installs land in node_modules, which is disposable in this
# workspace, so re-run this script after a workspace is restored/archived.
#
#   ./scripts/install-rea.sh
#
# Requires Node.js 22.x (>=22.23.4), 24.x (>=24.22.1) or 26.12.1+.
# Deep native analysis additionally needs Hopper, Ghidra, IDA or Kuna;
# Android APKs need a caller-supplied jadx-headless-mcp jar. Those providers
# are not installed here.
set -euo pipefail

export PATH="${HOME}/.npm-global/bin:${PATH}"
mkdir -p "${HOME}/.npm-global"
npm config set prefix "${HOME}/.npm-global"
npm install --global rea-agents

rea --version
echo
echo "REA installed. Add this to your shell before using it:"
echo "  export PATH=\"\$HOME/.npm-global/bin:\$PATH\""
