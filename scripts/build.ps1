param([switch]$CoreOnly, [switch]$DeviceTests, [switch]$RepairWindowsCache)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Push-Location -LiteralPath $projectRoot
try {
    $buildArgs = @('--gradle-user-home', '.gradle-user-home', '--daemon', '--max-workers=1', '--no-watch-fs', '-Dorg.gradle.internal.instrumentation.agent=false', ':core:test')
    if ($CoreOnly) { $buildArgs += '-PcoreOnly' }
    else { $buildArgs += @(':app:assembleDebug', ':app:lintDebug') }
    if ($DeviceTests) { $buildArgs += ':app:connectedDebugAndroidTest' }
    $attempts = if ($RepairWindowsCache) { 8 } else { 1 }
    New-Item -ItemType Directory -Path '.build-logs' -Force | Out-Null
    for ($attempt = 1; $attempt -le $attempts; $attempt++) {
        if ($RepairWindowsCache) {
            $transformRoot = Join-Path $projectRoot '.gradle-user-home\caches\8.14.3\transforms'
            if (Test-Path -LiteralPath $transformRoot) {
                Get-ChildItem -LiteralPath $transformRoot -Directory | Where-Object { $_.Name -match '^[a-f0-9]{32}-[a-f0-9-]{36}$' } | ForEach-Object {
                    $target = Join-Path $transformRoot $_.Name.Substring(0, 32)
                    # Copy only complete generated workspaces; never delete or move user files.
                    if (!(Test-Path -LiteralPath $target) -and (Test-Path -LiteralPath (Join-Path $_.FullName 'metadata.bin')) -and (Test-Path -LiteralPath (Join-Path $_.FullName 'results.bin'))) {
                        Copy-Item -LiteralPath $_.FullName -Destination $target -Recurse
                    }
                }
            }
        }
        & .\gradlew.bat @buildArgs 2>&1 | Tee-Object -FilePath '.build-logs\build.log'
        $buildExit = $LASTEXITCODE
        if ($buildExit -eq 0) { break }
        Copy-Item -LiteralPath '.build-logs\build.log' -Destination ".build-logs\attempt-$attempt.log" -Force
        if (!(Get-Content -LiteralPath '.build-logs\build.log' -Raw).Contains('Could not move temporary workspace')) { break }
    }
    exit $buildExit
} finally { Pop-Location }
