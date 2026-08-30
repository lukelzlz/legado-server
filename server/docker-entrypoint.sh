#!/bin/sh
set -eu

mkdir -p /data
chown -R legado:legado /data 2>/dev/null || chmod 777 /data 2>/dev/null || true

if command -v gosu >/dev/null 2>&1 && id -u legado >/dev/null 2>&1; then
    exec gosu legado "$@" 2>/dev/null || exec "$@"
else
    exec "$@"
fi
