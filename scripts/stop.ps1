$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$statePath = Join-Path $projectRoot 'runtime\processes.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Output 'No services started by this script were recorded.'; exit 0 }
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
foreach ($kind in @('java','python')) {
    $processId = [int]$state.($kind + 'Pid')
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($null -ne $process) {
        $expectedStart = [DateTime]::Parse($state.($kind + 'Started')).ToUniversalTime()
        if ([Math]::Abs(($process.StartTime.ToUniversalTime() - $expectedStart).TotalSeconds) -gt 1) {
            throw "PID $processId now belongs to a different process. Stop cancelled."
        }
        Stop-Process -Id $processId
    }
}
Remove-Item -LiteralPath $statePath
Write-Output 'Demo services stopped. The local database is preserved.'
