# Run database-free Java tests on Windows PowerShell with JDK 17+.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
& cmd.exe /c 'scripts\resolve-dependencies.bat'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('securebank-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out | Out-Null
try {
    $sources = @(Get-ChildItem -Path src,test -Recurse -Filter '*.java' -File | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
    $argfile = Join-Path $out 'sources.txt'
    [System.IO.File]::WriteAllLines($argfile, [string[]]$sources, (New-Object System.Text.UTF8Encoding($false)))
    & javac --release 17 -encoding UTF-8 -d $out -cp 'lib/*' "@$argfile"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $suites = @(
        'com.bank.fraud.FraudEngineSmokeTest'
        'com.bank.dao.impl.AccountDAOImplTest'
        'com.bank.service.CustomerProfileAccessTest'
        'com.bank.fraud.outbox.FraudOutboxWorkerTest'
        'com.bank.fraud.outbox.JdbcFraudOutboxRepositoryTest'
        'com.bank.fraud.outbox.FraudOutboxSchemaContractTest'
        'com.bank.graph.CustomerGraphTest'
        'com.bank.service.AdminOperationsGuardTest'
        'com.bank.service.CustomerLifecycleServiceTest'
        'com.bank.service.AccountClosurePolicyTest'
        'com.bank.service.CustomerLifecycleSchemaContractTest'
        'com.bank.service.CustomerPinRecoveryTest'
        'com.bank.service.CustomerInboxServiceTest'
        'com.bank.security.ConfiguredAdminAuthenticationServiceTest'
        'com.bank.security.DatabaseAdminAuthenticationServiceTest'
        'com.bank.security.CustomerSessionServiceTest'
        'com.bank.security.CustomerAccessGuardTest'
        'com.bank.registration.RegistrationServiceTest'
        'com.bank.registration.RegistrationDAOImplTest'
        'com.bank.security.LoginThrottleTest'
        'com.bank.config.DatabaseConfigTest'
        'com.bank.config.DatabaseConnectionPoolConfigTest'
        'com.bank.config.DatabaseConnectionEvictionTest'
        'com.bank.infrastructure.jdbc.JdbcTransactionExecutorTest'
        'com.bank.ArchitectureRulesTest'
        'com.bank.graph.infrastructure.JdbcCustomerGraphRepositoryTest'
        'com.bank.fraudring.FraudRingAnalyzerTest'
        'com.bank.fraudring.GraphIsolationForestTest'
        'com.bank.fraudring.FraudRingScoringTest'
        'com.bank.fraudring.FraudRingServiceTest'
        'com.bank.fraudring.JdbcFraudRingSourceRepositoryTest'
        'com.bank.fraudring.JdbcFraudRingResultRepositoryTest'
        'com.bank.view.admin.GraphLayoutAndCanvasTest'
        'com.bank.view.admin.FraudRingPanelTest'
        'com.bank.view.admin.CustomerGraphPanelTest'
        'com.bank.view.admin.FraudProcessingPanelTest'
    )
    $status = 0
    foreach ($suite in $suites) {
        Write-Host "=== $suite"
        & java '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' -cp "$out;lib/*" $suite
        if ($LASTEXITCODE -ne 0) { $status = 1 }
    }
    exit $status
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force
}
