#!/usr/bin/env bash
# Resolve the exact runtime/build dependencies pinned in pom.xml.
# Usage: ./scripts/resolve-dependencies.sh [--force]
set -euo pipefail
cd "$(dirname "$0")/.."

required=(
  "HikariCP-5.1.0.jar"
  "jbcrypt-0.4.jar"
  "protobuf-java-4.31.1.jar"
  "mysql-connector-j-9.7.0.jar"
  "slf4j-api-2.0.16.jar"
  "slf4j-simple-2.0.16.jar"
)
force="${1:-}"
missing=0
for jar in "${required[@]}"; do
  if [[ ! -s "lib/$jar" ]]; then missing=1; fi
done

if [[ "$missing" -eq 0 && "$force" != "--force" ]]; then
  echo "All required dependencies are present in lib/."
  exit 0
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: Maven 3.8+ is required to resolve SecureBank dependencies." >&2
  echo "Install Apache Maven, then run: ./scripts/resolve-dependencies.sh" >&2
  echo "The source archive intentionally excludes binary JAR dependencies." >&2
  exit 2
fi

mvn --batch-mode --no-transfer-progress dependency:copy-dependencies

missing_names=()
for jar in "${required[@]}"; do
  if [[ ! -s "lib/$jar" ]]; then missing_names+=("$jar"); fi
done
if ((${#missing_names[@]})); then
  printf 'ERROR: Maven completed but required artifacts are missing:
' >&2
  printf '  - %s
' "${missing_names[@]}" >&2
  exit 3
fi

echo "Resolved and verified all required dependencies."
