$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jarPath = Join-Path $projectRoot 'java-app\target\warehouse-workflow-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Build the frontend and Java package first; see README.' }
$pythonPath = (Get-Command python).Source
$venvPython = Join-Path $projectRoot '.venv\Scripts\python.exe'
if (Test-Path -LiteralPath $venvPython) { $pythonPath = $venvPython }
$javaPort = if ($env:JAVA_PORT) { [int]$env:JAVA_PORT } else { 18080 }
$pythonPort = if ($env:PYTHON_PORT) { [int]$env:PYTHON_PORT } else { 18081 }
foreach ($port in @($javaPort,$pythonPort)) {
    $client = New-Object System.Net.Sockets.TcpClient
    try { $client.Connect('127.0.0.1',$port); throw "Port $port is already in use. Check existing services." }
    catch [System.Net.Sockets.SocketException] { } finally { $client.Dispose() }
}
$runtime = Join-Path $projectRoot 'runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$env:JAVA_PORT = "$javaPort"
$env:PYTHON_URL = "http://127.0.0.1:$pythonPort"
$env:JAVA_URL = "http://127.0.0.1:$javaPort"
$env:PYTHONUTF8 = '1'
$pythonProcess = Start-Process -FilePath $pythonPath -ArgumentList @('-m','uvicorn','app.main:app','--host','127.0.0.1','--port',"$pythonPort") -WorkingDirectory (Join-Path $projectRoot 'fastapi-service') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'python.log') -RedirectStandardError (Join-Path $runtime 'python-error.log')
try {
    $javaProcess = Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar',('"' + $jarPath + '"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'java.log') -RedirectStandardError (Join-Path $runtime 'java-error.log')
    @{ javaPid=$javaProcess.Id; pythonPid=$pythonProcess.Id; javaStarted=$javaProcess.StartTime.ToUniversalTime().ToString('o'); pythonStarted=$pythonProcess.StartTime.ToUniversalTime().ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime 'processes.json') -Encoding utf8
    $ready = $false
    for ($attempt=0; $attempt -lt 40; $attempt++) {
        if ($javaProcess.HasExited -or $pythonProcess.HasExited) { throw 'A service exited; inspect runtime logs.' }
        try {
            $null = Invoke-RestMethod -Uri "http://127.0.0.1:$javaPort/health" -TimeoutSec 1
            $null = Invoke-RestMethod -Uri "http://127.0.0.1:$pythonPort/health" -TimeoutSec 1
            $ready = $true; break
        } catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $ready) { throw 'Services did not become ready; inspect runtime logs.' }
    Write-Output "Workbench: http://127.0.0.1:$javaPort"
    Write-Output 'Accounts: operator / viewer. Local demo password: demo-only. Stop: powershell -File scripts/stop.ps1'
} catch {
    if ($javaProcess -and -not $javaProcess.HasExited) { Stop-Process -Id $javaProcess.Id }
    if (-not $pythonProcess.HasExited) { Stop-Process -Id $pythonProcess.Id }
    throw
}
