#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$ROOT/aft-web"
npm install
npm run build
cd "$ROOT"
./mvnw "-Dmaven.repo.local=$ROOT/.m2-repository" clean package
