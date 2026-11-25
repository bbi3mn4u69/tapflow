#!/bin/sh
set -eu
umask 077
mkdir -p /tmp/tapflow-tls
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -sha256 -days 30 -nodes -subj /CN=db -keyout /tmp/tapflow-tls/server.key -out /tmp/tapflow-tls/server.crt >/dev/null 2>&1
exec docker-entrypoint.sh "$@"
