param(
    [string]$Cycle
)

$ErrorActionPreference = "Stop"
$Repo = Split-Path -Parent $PSScriptRoot
Set-Location $Repo

if ([string]::IsNullOrWhiteSpace($Cycle)) {
    $cycleDir = Get-ChildItem .\ml-data\local\runs -Directory |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
} else {
    $cycleDir = Get-Item (Join-Path .\ml-data\local\runs $Cycle)
}

if (-not $cycleDir) {
    throw "No ML cycle directory found."
}

Write-Host "========== MONSTERMAZE ML CYCLE REPORT =========="
Write-Host "Cycle: $($cycleDir.Name)"
Write-Host ""

$model = Join-Path $cycleDir.FullName "route-value-model-candidate.json"
if (Test-Path $model) {
    $m = Get-Content $model -Raw | ConvertFrom-Json

    Write-Host "========== MODEL =========="
    Write-Host "objective=$($m.objective)"
    Write-Host "rows=$($m.metrics.rows)"
    Write-Host "train_rows=$($m.metrics.train_rows)"
    Write-Host "validation_rows=$($m.metrics.validation_rows)"
    Write-Host "train_pairs=$($m.metrics.train_pairs)"
    Write-Host ("train_pairwise_accuracy={0:P2}" -f $m.metrics.train_pairwise_accuracy)
    Write-Host ("validation_pairwise_accuracy={0:P2}" -f $m.metrics.validation_pairwise_accuracy)
    Write-Host ("train_top1_accuracy={0:P2}" -f $m.metrics.train_top1_accuracy)
    Write-Host ("validation_top1_accuracy={0:P2}" -f $m.metrics.validation_top1_accuracy)
    Write-Host ("validation_pairwise_logloss={0:N4}" -f $m.metrics.validation_pairwise_logloss)
    Write-Host "pair_samples_per_epoch=$($m.metrics.pair_samples_per_epoch)"
    Write-Host ""
}

$training = Join-Path $cycleDir.FullName "training-window.jsonl"
if (Test-Path $training) {
    $rows = @(Get-Content $training | ForEach-Object {
        try { $_ | ConvertFrom-Json } catch {}
    })

    Write-Host "========== DATASET =========="
    Write-Host "rows=$($rows.Count)"

    $rows | Group-Object mode | Sort-Object Name | ForEach-Object {
        $reached = @($_.Group | Where-Object { $_.reached -eq $true }).Count
        Write-Host "$($_.Name): rows=$($_.Count) reached=$reached failed=$($_.Count - $reached)"
    }

    $rows | Group-Object kit | Sort-Object Name | ForEach-Object {
        $reached = @($_.Group | Where-Object { $_.reached -eq $true }).Count
        Write-Host "$($_.Name): rows=$($_.Count) reached=$reached failed=$($_.Count - $reached)"
    }

    Write-Host ""
}

$summaryPath = Join-Path $cycleDir.FullName "matrix-summary.json"

Write-Host "========== FULL MATRIX =========="

if (Test-Path $summaryPath) {
    $summary = Get-Content $summaryPath -Raw | ConvertFrom-Json

    Write-Host "observed_cases=$($summary.observed_cases)/$($summary.expected_full_matrix_cases)"
    Write-Host ("overall_baseline_avg={0:N2}" -f $summary.overall.baseline_avg)
    Write-Host ("overall_candidate_avg={0:N2}" -f $summary.overall.candidate_avg)
    $overallDeltaText = "{0:N2}" -f $summary.overall.delta_avg
    if ($summary.overall.delta_avg -gt 0) { $overallDeltaText = "+" + $overallDeltaText }
    Write-Host "overall_delta_avg=$overallDeltaText"
    Write-Host "overall_baseline_peak=$($summary.overall.baseline_peak)"
    Write-Host "overall_candidate_peak=$($summary.overall.candidate_peak)"
    Write-Host "overall_improved=$($summary.overall.improved) worsened=$($summary.overall.worsened) same=$($summary.overall.same)"
    Write-Host ""

    Write-Host "--- By mode ---"
    $summary.by_mode | ForEach-Object {
        $deltaText = "{0:N2}" -f $_.delta_avg
        if ($_.delta_avg -gt 0) { $deltaText = "+" + $deltaText }
        Write-Host ("{0,-7} cases={1,3} baselineAvg={2,6:N2} candidateAvg={3,6:N2} delta={4,7} peak={5,2} improved={6,2} worsened={7,2}" -f $_.mode, $_.cases, $_.baseline_avg, $_.candidate_avg, $deltaText, $_.candidate_peak, $_.improved, $_.worsened)
    }

    Write-Host ""
    Write-Host "--- By pattern ---"
    $summary.by_pattern | ForEach-Object {
        $deltaText = "{0:N2}" -f $_.delta_avg
        if ($_.delta_avg -gt 0) { $deltaText = "+" + $deltaText }
        Write-Host ("pattern={0} cases={1,3} baselineAvg={2,6:N2} candidateAvg={3,6:N2} delta={4,7} peak={5,2} improved={6,2} worsened={7,2}" -f $_.pattern, $_.cases, $_.baseline_avg, $_.candidate_avg, $deltaText, $_.candidate_peak, $_.improved, $_.worsened)
    }

    Write-Host ""
    Write-Host "--- By kit ---"
    $summary.by_kit | ForEach-Object {
        $deltaText = "{0:N2}" -f $_.delta_avg
        if ($_.delta_avg -gt 0) { $deltaText = "+" + $deltaText }
        Write-Host ("{0,-13} cases={1,3} baselineAvg={2,6:N2} candidateAvg={3,6:N2} delta={4,7} peak={5,2} improved={6,2} worsened={7,2}" -f $_.kit, $_.cases, $_.baseline_avg, $_.candidate_avg, $deltaText, $_.candidate_peak, $_.improved, $_.worsened)
    }

    Write-Host ""
    Write-Host "--- Every mode / pattern / kit ---"
    $summary.matrix | Sort-Object mode, pattern, kit | ForEach-Object {
        $deltaText = "{0:N2}" -f $_.delta_avg
        if ($_.delta_avg -gt 0) { $deltaText = "+" + $deltaText }
        Write-Host ("{0,-7} P{1} {2,-13} baseline={3,6:N2} candidate={4,6:N2} delta={5,7} peak={6,2} improved={7} worsened={8} same={9}" -f $_.mode, $_.pattern, $_.kit, $_.baseline_avg, $_.candidate_avg, $deltaText, $_.candidate_peak, $_.improved, $_.worsened, $_.same)
    }
} else {
    Write-Host "No matrix-summary.json found for this cycle."
}

Write-Host ""
Write-Host "========== RUNTIME / ML =========="

$allMarkers = @()
foreach ($log in Get-ChildItem $cycleDir.FullName -Filter "*.log" | Sort-Object Name) {
    $markers = @(Get-Content $log.FullName | Where-Object {
        $_ -match "ML_PREFILTER|ML_SHADOW|ML route model disabled|PROMOTED|REJECTED|Training rollout failed|Candidate matrix failed"
    })
    $allMarkers += $markers

    $prefilter = @($markers | Where-Object { $_ -match "ML_PREFILTER" }).Count
    $shadow = @($markers | Where-Object { $_ -match "ML_SHADOW" }).Count
    $disabled = @($markers | Where-Object { $_ -match "ML route model disabled" }).Count

    Write-Host "$($log.Name): prefilter=$prefilter shadow=$shadow disabled=$disabled"
}

$prefilterLines = @($allMarkers | Where-Object { $_ -match "ML_PREFILTER" })
if ($prefilterLines.Count -gt 0) {
    $seen = 0.0
    $selected = 0.0

    foreach ($line in $prefilterLines) {
        if ($line -match "candidates=(\d+)\s+selected=(\d+)") {
            $seen += [double]$Matches[1]
            $selected += [double]$Matches[2]
        }
    }

    if ($seen -gt 0) {
        Write-Host "prefilter_calls=$($prefilterLines.Count)"
        Write-Host ("avg_candidates_seen={0:N2}" -f ($seen / $prefilterLines.Count))
        Write-Host ("avg_candidates_simulated={0:N2}" -f ($selected / $prefilterLines.Count))
        Write-Host ("estimated_candidates_filtered={0:P1}" -f (1.0 - ($selected / $seen)))
    }
}

$current = Join-Path $Repo "ml-data\local\current\route-value-model.json"
$promoted = (Test-Path $model) -and (Test-Path $current) -and ((Get-FileHash $model).Hash -eq (Get-FileHash $current).Hash)

Write-Host ""
Write-Host "========== STATUS =========="
Write-Host "candidate_promoted=$promoted"

$gateFiles = @(Get-ChildItem $cycleDir.FullName -Filter "holdout-seed-*-gate.json" | Sort-Object Name)
foreach ($gate in $gateFiles) {
    $g = Get-Content $gate.FullName -Raw | ConvertFrom-Json
    Write-Host "$($gate.Name): passed=$($g.passed) improved=$($g.improved) worsened=$($g.worsened) same=$($g.same)"
}

Write-Host ""
Write-Host "========== END REPORT =========="
