# Dependencies

Do not add placeholder or hand-built replacement JARs here. Resolve the exact dependencies pinned in `pom.xml` from the repository root:

- Linux/macOS/Git Bash: `bash scripts/resolve-dependencies.sh`
- Windows: `scripts\resolve-dependencies.bat`

The resolver uses Maven 3.8+ and verifies that all pinned direct artifacts exist before the build or test runner proceeds. Maven also resolves transitive dependencies. The downloaded JARs are intentionally ignored by Git and excluded from the public source archive. See [third-party dependencies](../docs/THIRD_PARTY.md).
