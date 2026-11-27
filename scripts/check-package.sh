#!/usr/bin/env bash
# Run after ant clean jar. Requires JDK 17+.
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for role in Customer Admin; do
  jar="$PWD/dist/${role}Bank.jar"
  test -s "$jar"
  (cd "$work" && jar xf "$jar" META-INF/MANIFEST.MF)
  grep -q "Main-Class: com.bank.${role}App" "$work/META-INF/MANIFEST.MF"
  grep -q 'Class-Path: SecureBankCore.jar' "$work/META-INF/MANIFEST.MF"
  jar tf "$jar" | grep '\.class$' | sort > "$work/$role.classes"
  # Check each role against only its own runtime + common core + external libs.
  jdeps --multi-release 17 --missing-deps --class-path 'dist/SecureBankCore.jar:dist/lib/*' "$jar" dist/SecureBankCore.jar > "$work/$role.deps"
  test ! -s "$work/$role.deps" || { cat "$work/$role.deps"; exit 1; }
  set +e
  (cd "$work" && env -u BANK_DB_USER -u BANK_DB_PASSWORD -u BANK_DB_CONFIG -u BANK_DB_URL java -Djava.awt.headless=true -jar "$jar") > "$work/output" 2>&1
  result=$?
  set -e
  test "$result" -eq 2 || { cat "$work/output"; exit 1; }
  grep -qi 'Database credentials are not configured' "$work/output"
  echo "$role: manifest, missing-config startup and static dependencies PASS"
done
jar tf dist/SecureBankCore.jar | grep '\.class$' | sort > "$work/Core.classes"
cat "$work/Customer.classes" "$work/Core.classes" > "$work/customer-runtime"
if grep -Eq '^com/bank/(AdminApp|admin/|view/admin/|view/AdminLoginFrame|graph/|fraudring/|tools/|security/.*AdminAuthenticationService|security/LoginThrottle|fraud/outbox/(FraudOutboxWorker|JdbcFraudOutboxRepository|FraudEventSummary))' "$work/customer-runtime"; then
  echo 'Admin-only class leaked into customer runtime'; exit 1
fi
cat "$work/Admin.classes" "$work/Core.classes" > "$work/admin-runtime"
if grep -Eq '^com/bank/(CustomerApp|view/(LoginFrame|RegisterFrame|DashboardFrame)|view/dialog/(AmountDialog|TransferDialog|CustomerInboxDialog))' "$work/admin-runtime"; then
  echo 'Customer UI class leaked into admin runtime'; exit 1
fi
cat "$work/Customer.classes" "$work/Admin.classes" "$work/Core.classes" | sort > "$work/all"
test -z "$(uniq -d "$work/all")"
(cd build/classes && find com/bank -name '*.class' -type f | sort) > "$work/compiled"
diff -u "$work/compiled" "$work/all"
test ! -e dist/SecureBank.jar
for role in Customer Admin Core; do echo "$role classes: $(wc -l < "$work/$role.classes")"; done
echo 'Role-specific contents, complete class partition, no duplication and no combined JAR: PASS'
