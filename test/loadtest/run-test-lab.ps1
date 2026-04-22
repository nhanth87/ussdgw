# ============================================================
# USSD Gateway Test Lab Runner - 4G @ 10k TPS (Windows)
# ============================================================

param(
    [string]$ConfigFile = "configuration.conf",
    [string]$Mode = "http"  # http | ss7 | loopback
)

Write-Host "=== USSD Gateway Test Lab - 4G @ 10k TPS ===" -ForegroundColor Green
Write-Host "Config: $ConfigFile"
Write-Host "Mode: $Mode"
Write-Host ""

# Parse config
function Get-ConfigValue($key) {
    $line = Select-String -Path $ConfigFile -Pattern "^$key\s*=" | Select-Object -First 1
    if ($line) {
        return ($line.Line -split '=', 2)[1].Trim()
    }
    return $null
}

$TargetTps = Get-ConfigValue "target_tps"
$WorkerThreads = Get-ConfigValue "worker_threads"
$BaseUrl = Get-ConfigValue "base_url"
$Duration = Get-ConfigValue "duration_seconds"

# JVM Options for 10k TPS
$JvmOpts = @(
    "-XX:+UseG1GC",
    "-Xms8g", "-Xmx8g",
    "-XX:MaxGCPauseMillis=20",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseStringDeduplication",
    "-XX:+AlwaysPreTouch",
    "-XX:+DisableExplicitGC",
    "-XX:+ParallelRefProcEnabled"
) -join " "

# Build if needed
function Build-Tools {
    Write-Host "[BUILD] Checking build artifacts..." -ForegroundColor Yellow
    
    if (-not (Test-Path "target\loadtest-7.2.1-SNAPSHOT.jar")) {
        Write-Host "[BUILD] Building loadtest module..." -ForegroundColor Yellow
        mvn clean package -DskipTests -q
    }
    
    Write-Host "[BUILD] Ready" -ForegroundColor Green
}

# Run HTTP Load Test
function Run-HttpTest {
    Write-Host "[TEST] Starting HTTP Load Test - Target: $TargetTps TPS" -ForegroundColor Green
    Write-Host "URL: $BaseUrl"
    Write-Host "Threads: $WorkerThreads"
    Write-Host "Duration: ${Duration}s"
    Write-Host ""
    
    $MaxConcurrent = [int]$TargetTps * 5
    
    $cmd = "java $JvmOpts -cp `"target\loadtest-7.2.1-SNAPSHOT.jar;target\dependency\*`" org.mobicents.ussd.loadtest.UssdHttpLoadGenerator `"$BaseUrl`" $TargetTps $WorkerThreads $MaxConcurrent"
    Write-Host "Command: $cmd" -ForegroundColor Cyan
    Invoke-Expression $cmd
}

# Run Loopback Test
function Run-LoopbackTest {
    Write-Host "[TEST] Starting Loopback Load Test - Target: $TargetTps TPS" -ForegroundColor Green
    Write-Host "Mode: Local benchmark (no real SS7 transport)" -ForegroundColor Yellow
    Write-Host ""
    
    # Build classpath
    $cp = "target\loadtest-7.2.1-SNAPSHOT.jar;target\dependency\*"
    
    # Start server
    Write-Host "[SERVER] Starting load server..." -ForegroundColor Yellow
    $serverProc = Start-Process -FilePath "java" -ArgumentList "$JvmOpts -cp `"$cp`" org.mobicents.ussd.loadtest.UssdLoadTestMain --server --loopback --ssn 8" -PassThru -NoNewWindow
    
    Start-Sleep -Seconds 2
    
    # Start client
    Write-Host "[CLIENT] Starting load generator..." -ForegroundColor Green
    java $JvmOpts -cp "$cp" org.mobicents.ussd.loadtest.UssdLoadTestMain --client --loopback --tps $TargetTps --threads $WorkerThreads --ssn 8
    
    # Cleanup
    if ($serverProc -and -not $serverProc.HasExited) {
        Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue
    }
}

# Run SS7 Test
function Run-Ss7Test {
    Write-Host "[TEST] Starting Real SS7 Load Test" -ForegroundColor Green
    Write-Host "[WARN] Requires test/bootstrap with SS7 config" -ForegroundColor Yellow
    Write-Host ""
    
    if (-not (Test-Path "..\bootstrap\target\ussd-server")) {
        Write-Host "[BUILD] Bootstrap not built. Building..." -ForegroundColor Yellow
        Push-Location "..\bootstrap"
        mvn clean package -DskipTests -q
        Pop-Location
    }
    
    Write-Host "[INFO] Start bootstrap first: cd ..\bootstrap && run.bat" -ForegroundColor Yellow
    Write-Host "[INFO] Then run load generator manually" -ForegroundColor Yellow
}

# Main
switch ($Mode) {
    "http" {
        Build-Tools
        Run-HttpTest
    }
    "loopback" {
        Build-Tools
        Run-LoopbackTest
    }
    "ss7" {
        Run-Ss7Test
    }
    default {
        Write-Host "Usage: .\run-test-lab.ps1 [-ConfigFile <file>] [-Mode http|loopback|ss7]"
        Write-Host ""
        Write-Host "Modes:"
        Write-Host "  http     - HTTP interface load test (requires running USSD GW)"
        Write-Host "  loopback - Local benchmark without network"
        Write-Host "  ss7      - Real SS7/SIGTRAN test (requires bootstrap stack)"
    }
}

Write-Host "[DONE] Test completed" -ForegroundColor Green
