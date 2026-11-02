#!/usr/bin/env bash
# 项目内可复现的真实 PostgreSQL 17 测试入口。
# 与一次性 target/run-pg.sh 等价，但不依赖 build 产物；缺少本地工具时明确退出。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PG_BIN="$ROOT/.tools/pg/bin"
JDK="$ROOT/.tools/jdk-21.0.13+9"
MVN="$ROOT/.tools/apache-maven-3.9.11/bin/mvn"
REPO="$ROOT/.tools/m2"
PG_DIR="$ROOT/target/pg-tests"
PORT="${PG_PORT:-55441}"

if [ ! -x "$PG_BIN/initdb" ] || [ ! -d "$JDK" ] || [ ! -x "$MVN" ]; then
  echo "缺少本地工具：需要 .tools/pg/bin、.tools/jdk-21.0.13+9、.tools/apache-maven-3.9.11" >&2
  exit 2
fi

if [ ! -d "$PG_DIR/data" ]; then
  rm -rf "$PG_DIR"
  mkdir -p "$PG_DIR/sock"
  "$PG_BIN/initdb" -D "$PG_DIR/data" -U postgres -A trust --no-locale -E UTF8 > "$PG_DIR/initdb.log" 2>&1
fi
mkdir -p "$PG_DIR/sock"

"$PG_BIN/pg_ctl" -D "$PG_DIR/data" -l "$PG_DIR/server.log" \
  -o "-p $PORT -k $PG_DIR/sock -c listen_addresses=127.0.0.1 -c fsync=off -c max_connections=100" \
  -w start >/dev/null

cleanup() {
  "$PG_BIN/pg_ctl" -D "$PG_DIR/data" -m immediate stop >/dev/null 2>&1 || true
}
trap cleanup EXIT

export TEST_DB_URL="jdbc:postgresql://127.0.0.1:${PORT}/postgres?currentSchema=gulupai_test"
export TEST_DB_USERNAME=postgres
export TEST_DB_PASSWORD=
export JAVA_HOME="$JDK"

cd "$ROOT"
MAVEN_OPTS="" "$MVN" -o \
  -Dmaven.repo.local="$REPO" \
  -DargLine="-javaagent:$REPO/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar" \
  "$@"
