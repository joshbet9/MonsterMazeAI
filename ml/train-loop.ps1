param(
    [int]$MaxCycles = 0,
    [int]$TrainingSeedsPerCycle = 4,
    [int]$HoldoutSeeds = 3,
    [int]$ReplayCycles = 12,
    [int]$Epochs = 250,
    [int]$BatchSize = 256,
    [int]$SleepSeconds = 2
)

$ErrorActionPreference = "Stop"
$Repo = Split-Path -Parent $PSScriptRoot
Set-Location $Repo

$Venv = Join-Path $Repo ".venv"
$Python = Join-Path $Venv "Scripts\python.exe"
$DataRoot = Join-Path $Repo "ml-data\local"
$HoldoutRoot = Join-Path $DataRoot "holdout"
$ReplayRoot = Join-Path $DataRoot "replay"
$CheckpointRoot = Join-Path $DataRoot "checkpoints"
$CurrentRoot = Join-Path $DataRoot "current"
$RunRoot = Join-Path $DataRoot "runs"
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Append-Utf8NoBom {
    param([string]$Source,[string]$Destination)
    $reader = [System.IO.StreamReader]::new($Source, $Utf8NoBom, $true)
    $writer = [System.IO.StreamWriter]::new($Destination, $true, $Utf8NoBom)
    try {
        while (($line = $reader.ReadLine()) -ne $null) {
            $writer.WriteLine($line)
        }
    } finally {
        $reader.Dispose()
        $writer.Dispose()
    }
}

foreach ($dir in @($DataRoot, $HoldoutRoot, $ReplayRoot, $CheckpointRoot, $CurrentRoot, $RunRoot)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

if (-not (Test-Path $Python)) {
    $HostPython = Get-Command python -ErrorAction SilentlyContinue
    if (-not $HostPython) {
        throw "Python is required to create the ML virtual environment. Install Python 3.11+ and ensure 'python' is on PATH."
    }
    Write-Host "Creating ML virtual environment with $($HostPython.Source)"
    & $HostPython.Source -m venv $Venv
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Python)) {
        throw "Failed to create ML virtual environment at $Venv."
    }
}

& $Python -m pip install --disable-pip-version-check --upgrade pip
if ($LASTEXITCODE -ne 0) { throw "Failed to upgrade pip." }
& $Python -m pip install --disable-pip-version-check numpy
if ($LASTEXITCODE -ne 0) { throw "Failed to install numpy." }

function Invoke-Matrix {
    param(
        [long]$SeedOffset,
        [string]$LogPath,
        [string]$TrainingPath,
        [string]$ModelPath
    )

    $args = @(
        "-B", "-ntp", "-pl", "common", "-am",
        "-Dtest=SpeedFullRunDiagnosticTest,ModernFullRunDiagnosticTest",
        "-Dmonstermaze.sim.seedOffset=$SeedOffset",
        "-Dmonstermaze.ml.record=true",
        "-Dmonstermaze.ml.output=$TrainingPath",
        "-Dsurefire.useFile=false",
        "-Dsurefire.redirectTestOutputToFile=false",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "test"
    )

    if ($ModelPath) {
        $args = @(
            "-B", "-ntp", "-pl", "common", "-am",
            "-Dtest=SpeedFullRunDiagnosticTest,ModernFullRunDiagnosticTest",
            "-Dmonstermaze.sim.seedOffset=$SeedOffset",
            "-Dmonstermaze.ml.record=false",
            "-Dmonstermaze.ml.model=$ModelPath",
            "-Dmonstermaze.ml.mode=prefilter",
            "-Dsurefire.useFile=false",
            "-Dsurefire.redirectTestOutputToFile=false",
            "-Dsurefire.failIfNoSpecifiedTests=false",
            "test"
        )
    }

    Write-Host "Running seedOffset=$SeedOffset"

    # Maven/Java tests legitimately write diagnostics to stderr. With the
    # script-wide ErrorActionPreference=Stop, PowerShell can otherwise promote
    # those native stderr lines into a terminating NativeCommandError before
    # we get a chance to inspect Maven's real exit code.
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & mvn.cmd @args 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    foreach ($line in $output) { Write-Host $line }
    [System.IO.File]::WriteAllLines($LogPath, [string[]]$output, $Utf8NoBom)
    return $code
}

function Merge-ReplayWindow {
    param([string]$OutputPath)

    $files = Get-ChildItem $ReplayRoot -Filter "*.jsonl" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First $ReplayCycles

    if (-not $files) {
        throw "No replay files exist."
    }

    Remove-Item $OutputPath -ErrorAction SilentlyContinue
    foreach ($file in ($files | Sort-Object LastWriteTime)) {
        Append-Utf8NoBom -Source $file.FullName -Destination $OutputPath
    }
}

function Invoke-Gate {
    param(
        [string]$Candidate,
        [string]$CycleDir
    )

    $ok = $true

    for ($i = 1; $i -le $HoldoutSeeds; $i++) {
        $baseline = Join-Path $HoldoutRoot "seed-$i-baseline.log"
        $candidateLog = Join-Path $CycleDir "holdout-seed-$i.log"
        $report = Join-Path $CycleDir "holdout-seed-$i-gate.json"

        if (-not (Test-Path $baseline)) {
            Write-Host "Creating fixed holdout baseline seedOffset=$i"
            $code = Invoke-Matrix $i $baseline (Join-Path $CycleDir "ignored-baseline-$i.jsonl")
            if ($code -ne 0) {
                throw "Holdout baseline failed for seed $i."
            }
        }

        $code = Invoke-Matrix $i $candidateLog (Join-Path $CycleDir "ignored-candidate-$i.jsonl") $Candidate
        if ($code -ne 0) {
            Write-Host "Candidate matrix failed for holdout seed $i."
            $ok = $false
            continue
        }

        & $Python (Join-Path $PSScriptRoot "evaluate_model.py") --baseline $baseline --candidate $candidateLog --json-output $report
        if ($LASTEXITCODE -ne 0) {
            $ok = $false
        }
    }

    return $ok
}

$cycle = 0

while ($MaxCycles -eq 0 -or $cycle -lt $MaxCycles) {
    $cycle++
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
    $cycleDir = Join-Path $RunRoot "$stamp-cycle-$cycle"
    New-Item -ItemType Directory -Force -Path $cycleDir | Out-Null
    $replayFile = Join-Path $ReplayRoot "$stamp-cycle-$cycle.jsonl"

    Write-Host ""
    Write-Host "================ LOCAL ML CYCLE $cycle ================"

    $rng = [Random]::new()

    for ($s = 1; $s -le $TrainingSeedsPerCycle; $s++) {
        $offset = $rng.Next(10000, 2000000000)
        $log = Join-Path $cycleDir "seed-$offset.log"
        $training = Join-Path $cycleDir "seed-$offset.jsonl"

        $code = Invoke-Matrix $offset $log $training
        if ($code -ne 0) {
            Write-Host "Training rollout failed: seedOffset=$offset"
            continue
        }

        if (Test-Path $training) {
            Append-Utf8NoBom -Source $training -Destination $replayFile
        }
    }

    if (-not (Test-Path $replayFile) -or (Get-Item $replayFile).Length -eq 0) {
        Write-Host "No new simulator labels; skipping this cycle."
        continue
    }

    $window = Join-Path $cycleDir "training-window.jsonl"
    Merge-ReplayWindow $window

    $candidate = Join-Path $cycleDir "route-value-model-candidate.json"

    & $Python (Join-Path $PSScriptRoot "route_ranker.py") train --input $window --output $candidate --epochs $Epochs --batch-size $BatchSize --hidden1 32 --hidden2 16 --learning-rate 0.002 --validation-fraction 0.20 --min-samples 100 --seed $cycle

    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $candidate)) {
        Write-Host "Model training failed; current model remains unchanged."
        continue
    }

    if (Invoke-Gate $candidate $cycleDir) {
        Copy-Item $candidate (Join-Path $CheckpointRoot "route-value-model-$stamp.json") -Force
        Copy-Item $candidate (Join-Path $CurrentRoot "route-value-model.json") -Force
        Write-Host "PROMOTED candidate after all fixed holdout gates passed."
    } else {
        Write-Host "REJECTED candidate; current model remains unchanged."
    }

    if ($SleepSeconds -gt 0) {
        Start-Sleep -Seconds $SleepSeconds
    }
}
