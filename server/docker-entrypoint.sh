#!/bin/sh
set -eu

mkdir -p /data
chown legado:legado /data
exec gosu legado "$@"
