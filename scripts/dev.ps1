param(
    [ValidateSet('Build','Run','Test','PreviewLight','PreviewDark','PreviewWallet','PreviewWalletEmpty')][string]$Action = 'Run',
    [string]$DeviceId,
    [string]$Avd = 'Medium_Phone_API_37.0'
)
$ErrorActionPreference = 'Stop'
$projectPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$settings = Get-Content -LiteralPath (Join-Path $projectPath '.vscode/settings.json') -Raw | ConvertFrom-Json
$env:JAVA_HOME = $settings.'java.import.gradle.java.home'
$env:ANDROID_HOME = $settings.'terminal.integrated.env.windows'.ANDROID_HOME
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:PATH"
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
function Invoke-Adb {
    param([string[]]$Arguments)
    $lines = @(& $adb @Arguments)
    if ($Arguments -contains 'start' -and $Arguments -contains '-n') {
        $expected = $Arguments[[Array]::IndexOf($Arguments, '-n') + 1]
        for ($retry = 0; $retry -lt 5; $retry++) {
            $activity = ($lines | Where-Object { $_ -match '^Activity: ' } | Select-Object -Last 1) -replace '^Activity: ', ''
            if ($activity -eq $expected) { break }
            Start-Sleep -Seconds 2
            $lines = @(& $adb @Arguments)
        }
        $activity = ($lines | Where-Object { $_ -match '^Activity: ' } | Select-Object -Last 1) -replace '^Activity: ', ''
        if ($activity -ne $expected) { throw "Android opened '$activity' instead of '$expected'. Wait for the emulator to settle and retry." }
    }
    $code = $LASTEXITCODE
    $lines | ForEach-Object { Write-Output $_ }
    if ($code -ne 0) { throw "adb failed: $($Arguments -join ' ')" }
    if ($Arguments -contains 'start' -and ($lines -join "`n") -match '(?m)^(Status: timeout|Error:|Exception)') {
        throw 'Android could not display the activity. Check the emulator for a system error, dismiss it, and retry.'
    }
}
Push-Location -LiteralPath $projectPath
try {
    $buildTask = if ($Action -eq 'Test') { ':core:test' } else { ':app:assembleDebug' }
    & .\gradlew.bat $buildTask --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle failed. See the build output above.' }
    if ($Action -in @('Build','Test')) { exit 0 }
    if (!$DeviceId) {
        $devices = @(& $adb devices | Select-String '^\S+\s+device\s*$' | ForEach-Object { ($_.Line -split '\s+')[0] })
        if ($devices.Count -gt 1) { throw 'Multiple devices are connected. Run this script with -DeviceId SERIAL.' }
        if ($devices.Count -eq 1) { $DeviceId = $devices[0] }
        else {
            $offline = @(& $adb devices | Select-String '^\S+\s+offline\s*$')
            if ($offline.Count) { throw 'A device is offline. In Android Studio Device Manager, stop it and choose Cold Boot; then retry. Device data is preserved.' }
            $emulator = Join-Path $env:ANDROID_HOME 'emulator/emulator.exe'
            $available = @(& $emulator -list-avds)
            if ($Avd -notin $available) { throw "AVD '$Avd' not found. Create it in Android Studio Device Manager." }
            Start-Process -FilePath $emulator -ArgumentList '-avd',$Avd -WindowStyle Hidden
            Write-Host 'Waiting for the Android emulator (up to 3 minutes)...'
            $deadline = (Get-Date).AddMinutes(3)
            do {
                Start-Sleep -Seconds 2
                $devices = @(& $adb devices | Select-String '^emulator-\d+\s+device\s*$' | ForEach-Object { ($_.Line -split '\s+')[0] })
                if ($devices.Count -eq 1) { $DeviceId = $devices[0] }
            } until ($DeviceId -or (Get-Date) -gt $deadline)
            if (!$DeviceId) { throw 'Emulator did not connect. Check Android Studio Device Manager.' }
        }
    }
    $deadline = (Get-Date).AddMinutes(3)
    do {
        $boot = (& $adb -s $DeviceId shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($boot -ne '1') { Start-Sleep -Seconds 2 }
    } until ($boot -eq '1' -or (Get-Date) -gt $deadline)
    if ($boot -ne '1') { throw 'Android did not finish booting. Try Cold Boot in Device Manager.' }
    Invoke-Adb -Arguments @('-s',$DeviceId,'install','-r',(Join-Path $projectPath 'app/build/outputs/apk/debug/app-debug.apk'))
    Start-Sleep -Seconds 2
    if ($Action -eq 'Run') {
        Invoke-Adb -Arguments @('-s',$DeviceId,'shell','am','start','-W','-n','com.allowance.app/.MainActivity')
        Write-Host "Allowance is running on $DeviceId."
    } else {
        $function = switch ($Action) {
            'PreviewDark' { 'TodayDarkPreview' }
            'PreviewWallet' { 'WalletDesignPreview' }
            'PreviewWalletEmpty' { 'WalletEmptyPreview' }
            default { 'TodayLightPreview' }
        }
        $previewClass = if ($Action -like 'PreviewWallet*') { 'WalletPreviewsKt' } else { 'AllowancePreviewsKt' }
        Invoke-Adb -Arguments @('-s',$DeviceId,'shell','am','start','-W','-S','-n','com.allowance.app/androidx.compose.ui.tooling.PreviewActivity','--es','composable',"com.allowance.app.ui.${previewClass}.$function")
        Start-Sleep -Seconds 3
        $previewDir = Join-Path $projectPath 'app/build/previews'
        [void][IO.Directory]::CreateDirectory($previewDir)
        $output = Join-Path $previewDir "$function.png"
        $remote = "/sdcard/Allowance-$function.png"
        Invoke-Adb -Arguments @('-s',$DeviceId,'shell','screencap','-p',$remote)
        Invoke-Adb -Arguments @('-s',$DeviceId,'pull',$remote,$output)
        Invoke-Adb -Arguments @('-s',$DeviceId,'shell','am','start','-W','-n','com.allowance.app/.MainActivity')
        Write-Host "Preview image: $output"
        $code = Join-Path $env:LOCALAPPDATA 'Programs/Microsoft VS Code/bin/code.cmd'
        if (Test-Path -LiteralPath $code) { & $code --reuse-window $output }
    }
} finally { Pop-Location }
