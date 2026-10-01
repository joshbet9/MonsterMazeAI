param(
    [int]$MaxCycles = 1,
    [int]$TrainingMatricesPerCycle = 1,
    [int]$HoldoutSeeds = 3,
    [int]$FullGateEveryCycles = 5,
    [int]$ReplayCycles = 8,
    [int]$Epochs = 80,
    [int]$BatchSize = 512,
    [int]$SamplesPerEpoch = 50000,
    [double]$Gamma = 0.995,
    [double]$Exploration = 0.20,
    [int]$CounterfactualStride = 20,
    [int]$CounterfactualHorizon = 32,
    [int]$SleepSeconds = 0
)

$ErrorActionPreference = "Stop"
$Repo = Split-Path -Parent $PSScriptRoot
Set-Location $Repo
$Venv = Join-Path $Repo ".venv"
$Python = Join-Path $Venv "Scripts\python.exe"
$DataRoot = Join-Path $Repo "ml-data\local-counterfactual-policy"
$HoldoutRoot = Join-Path $DataRoot "holdout"
$ReplayRoot = Join-Path $DataRoot "replay"
$CheckpointRoot = Join-Path $DataRoot "checkpoints"
$CurrentRoot = Join-Path $DataRoot "current"
$RunRoot = Join-Path $DataRoot "runs"

foreach ($dir in @($DataRoot,$HoldoutRoot,$ReplayRoot,$CheckpointRoot,$CurrentRoot,$RunRoot)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

if (-not (Test-Path $Python)) {
    $HostPython = Get-Command python -ErrorAction SilentlyContinue
    if (-not $HostPython) { throw "Python 3.11+ is required." }
    & $HostPython.Source -m venv $Venv
    if ($LASTEXITCODE -ne 0) { throw "Failed to create Python virtual environment." }
}
& $Python -m pip install --disable-pip-version-check numpy
if ($LASTEXITCODE -ne 0) { throw "Failed to install numpy." }

function Invoke-Run {
    param([long]$SeedOffset,[string]$LogPath,[string]$TrainingPath,[string]$ModelPath,[bool]$Explore)
    $args = @("-B","-ntp","-pl","common","-am","-Dtest=SpeedFullRunDiagnosticTest,ModernFullRunDiagnosticTest","-Dmonstermaze.sim.seedOffset=$SeedOffset","-Dmonstermaze.ml.policy.record=false","-Dmonstermaze.ml.policy.counterfactual=$Explore","-Dmonstermaze.ml.policy.counterfactual.stride=$CounterfactualStride","-Dmonstermaze.ml.policy.counterfactual.horizon=$CounterfactualHorizon","-Dmonstermaze.ml.policy.counterfactual.gamma=$Gamma","-Dmonstermaze.ml.policy.output=$TrainingPath","-Dmonstermaze.ml.policy.epsilon=$Exploration","-Dmonstermaze.ml.policy.explore=$Explore","-Dsurefire.useFile=false","-Dsurefire.redirectTestOutputToFile=false","-Dsurefire.failIfNoSpecifiedTests=false","test")
    if ($ModelPath) {
        $args += "-Dmonstermaze.ml.policy.model=$ModelPath"
        $args += "-Dmonstermaze.ml.mode=policy"
    }
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { $output = & mvn.cmd @args 2>&1; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $old }
    foreach ($line in $output) { Write-Host $line }
    [System.IO.File]::WriteAllLines($LogPath,[string[]]$output,[System.Text.UTF8Encoding]::new($false))
    return $code
}

function Append-File {
    param([string]$Source,[string]$Destination)
    Get-Content $Source | Add-Content -Encoding utf8 $Destination
}

function Gate-AgainstBaseline {
    param([string]$Candidate,[int]$Seed,[string]$CycleDir)
    $baseline = Join-Path $HoldoutRoot "seed-$Seed-baseline.log"
    if (-not (Test-Path $baseline)) {
        $baselineLog = Join-Path $CycleDir "baseline-seed-$Seed.log"
        $baselineData = Join-Path $CycleDir "ignored-baseline.jsonl"
        $code = Invoke-Run $Seed $baselineLog $baselineData $null $false
        if ($code -ne 0) { throw "Policy holdout baseline failed for seed $Seed." }
        Copy-Item $baselineLog $baseline -Force
    }

    $candidateLog = Join-Path $CycleDir "holdout-seed-$Seed.log"
    $code = Invoke-Run $Seed $candidateLog (Join-Path $CycleDir "ignored-$Seed.jsonl") $Candidate $false
    if ($code -ne 0) { return $false }

    $baselineLines = @(Select-String -Path $baseline -Pattern "SPEED_FULL_RUN|MODERN_FULL_RUN" | ForEach-Object { $_.Line })
    $candidateLines = @(Select-String -Path $candidateLog -Pattern "SPEED_FULL_RUN|MODERN_FULL_RUN" | ForEach-Object { $_.Line })
    if ($baselineLines.Count -ne 30 -or $candidateLines.Count -ne 30) { return $false }

    $b = @{}
    foreach ($line in $baselineLines) {
        if ($line -match "(SPEED|MODERN)_FULL_RUN pattern=(\d) kit=([A-Z_]+) maxStage=(\d+)") {
            $b["$($matches[1])|$($matches[2])|$($matches[3])"] = [int]$matches[4]
        }
    }
    $c = @{}
    foreach ($line in $candidateLines) {
        if ($line -match "(SPEED|MODERN)_FULL_RUN pattern=(\d) kit=([A-Z_]+) maxStage=(\d+)") {
            $c["$($matches[1])|$($matches[2])|$($matches[3])"] = [int]$matches[4]
        }
    }
    if ($b.Count -ne 30 -or $c.Count -ne 30) { return $false }

    $baseSum=0; $candSum=0; $basePeak=0; $candPeak=0; $improved=0; $worsened=0
    foreach ($key in $b.Keys) {
        if (-not $c.ContainsKey($key)) { return $false }
        $bv=$b[$key]; $cv=$c[$key]
        $baseSum += $bv; $candSum += $cv
        $basePeak=[Math]::Max($basePeak,$bv); $candPeak=[Math]::Max($candPeak,$cv)
        if ($cv -gt $bv) { $improved++ }
        if ($cv -lt $bv) { $worsened++ }
    }

    $count=$b.Count
    $report=[ordered]@{seed=$Seed;cases=$count;baselineAvg=($baseSum/$count);candidateAvg=($candSum/$count);baselinePeak=$basePeak;candidatePeak=$candPeak;improved=$improved;worsened=$worsened;same=($count-$improved-$worsened);passed=($worsened -eq 0 -and $candSum -ge $baseSum -and $candPeak -ge $basePeak)}
    $report | ConvertTo-Json | Set-Content (Join-Path $CycleDir "policy-holdout-seed-$Seed.json") -Encoding utf8
    Write-Host ("POLICY_GATE seed={0} avg={1:N2}->{2:N2} peak={3}->{4} improved={5} worsened={6} passed={7}" -f $Seed,$report.baselineAvg,$report.candidateAvg,$report.baselinePeak,$report.candidatePeak,$report.improved,$report.worsened,$report.passed)
    return [bool]$report.passed
}

$cycle=0
while($MaxCycles -eq 0 -or $cycle -lt $MaxCycles){
    $cycle++
    $stamp=Get-Date -Format "yyyyMMdd-HHmmss-fff"
    $cycleDir=Join-Path $RunRoot "$stamp-cycle-$cycle"
    New-Item -ItemType Directory -Force $cycleDir | Out-Null
    $newReplay=Join-Path $ReplayRoot "$stamp.jsonl"
    Remove-Item $newReplay -ErrorAction SilentlyContinue
    Write-Host "================ LOCAL POLICY ML CYCLE $cycle ================"

    $currentModel=Join-Path $CurrentRoot "policy-model.json"
    $hasCurrent=Test-Path $currentModel
    $rng=[Random]::new()

    for($s=1;$s -le $TrainingMatricesPerCycle;$s++){
        $seed=$rng.Next(10000,2000000000)
        $log=Join-Path $cycleDir "explore-$seed.log"
        $data=Join-Path $cycleDir "explore-$seed.jsonl"
        $model=if($hasCurrent){$currentModel}else{$null}
        $code=Invoke-Run $seed $log $data $model $true
        if($code -eq 0 -and (Test-Path $data)){ Append-File $data $newReplay }
    }

    if(-not (Test-Path $newReplay) -or (Get-Item $newReplay).Length -eq 0){ Write-Host "No policy rollout data; skipping cycle."; continue }

    $window=Join-Path $cycleDir "training-window.jsonl"
    Remove-Item $window -ErrorAction SilentlyContinue
    $files=Get-ChildItem $ReplayRoot -Filter "*.jsonl" | Sort-Object LastWriteTime -Descending | Select-Object -First $ReplayCycles
    foreach($file in ($files | Sort-Object LastWriteTime)){ Append-File $file.FullName $window }

    $candidate=Join-Path $cycleDir "policy-model-candidate.json"
    & $Python (Join-Path $PSScriptRoot "policy_trainer.py") --input $window --output $candidate --epochs $Epochs --batch-size $BatchSize --samples-per-epoch $SamplesPerEpoch --hidden1 48 --hidden2 24 --learning-rate 0.001 --gamma $Gamma --validation-fraction 0.20 --min-samples 500 --seed $cycle --objective counterfactual_short_horizon_return
    if($LASTEXITCODE -ne 0 -or -not (Test-Path $candidate)){ Write-Host "Policy training failed; current policy remains unchanged."; continue }

    $matrixSeed=(($cycle-1)%$HoldoutSeeds)+1
    $full=($cycle%$FullGateEveryCycles)-eq 0
    $ok=Gate-AgainstBaseline $candidate $matrixSeed $cycleDir

    if($full){
        for($seed=1;$seed -le $HoldoutSeeds;$seed++){
            if($seed -eq $matrixSeed){continue}
            if(-not (Gate-AgainstBaseline $candidate $seed $cycleDir)){$ok=$false}
        }
    } else {$ok=$false}

    $reports=Get-ChildItem $cycleDir -Filter "policy-holdout-seed-*.json" | Sort-Object Name
    if($reports.Count -gt 0){
        $loaded=@($reports | ForEach-Object { Get-Content $_.FullName -Raw | ConvertFrom-Json })
        $loaded | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $cycleDir "policy-matrix-summary.json") -Encoding utf8
    }

    if($full -and $ok){
        Copy-Item $candidate (Join-Path $CheckpointRoot "policy-model-$stamp.json") -Force
        Copy-Item $candidate $currentModel -Force
        Write-Host "POLICY PROMOTED after fixed full holdout gate."
    } else { Write-Host "POLICY NOT PROMOTED." }

    if($SleepSeconds -gt 0){Start-Sleep -Seconds $SleepSeconds}
}
