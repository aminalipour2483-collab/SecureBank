@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
set "missing="
for %%J in ("HikariCP-5.1.0.jar" "jbcrypt-0.4.jar" "mysql-connector-j-9.7.0.jar" "protobuf-java-4.31.1.jar" "slf4j-api-2.0.16.jar" "slf4j-simple-2.0.16.jar") do (
  if not exist "lib\%%~J" set "missing=1"
  if exist "lib\%%~J" for %%F in ("lib\%%~J") do if %%~zF EQU 0 set "missing=1"
)
if not defined missing (
  echo All required dependencies are present in lib/.
  exit /b 0
)
where mvn >nul 2>&1
if errorlevel 1 (
  echo ERROR: Apache Maven 3.8+ is required to resolve SecureBank dependencies.
  echo Install Maven and ensure mvn is on PATH, then rerun this script.
  echo Binary JAR dependencies are intentionally excluded from the source archive.
  exit /b 2
)
call mvn --batch-mode --no-transfer-progress dependency:copy-dependencies
if errorlevel 1 exit /b %errorlevel%
for %%J in ("HikariCP-5.1.0.jar" "jbcrypt-0.4.jar" "mysql-connector-j-9.7.0.jar" "protobuf-java-4.31.1.jar" "slf4j-api-2.0.16.jar" "slf4j-simple-2.0.16.jar") do (
  if not exist "lib\%%~J" (
    echo ERROR: Required dependency is missing: %%~J
    exit /b 3
  )
)
echo Resolved and verified all required dependencies.
exit /b 0
