$ErrorActionPreference="Stop"
$Repo=Split-Path -Parent $PSScriptRoot
Set-Location $Repo

function Read-PolicyJson {
  param([string]$Path)
  $raw=Get-Content $Path -Raw -Encoding utf8
  # Older trainers accidentally wrote a literal backslash-n after the JSON.
  if($raw.EndsWith('\n')) { $raw=$raw.Substring(0,$raw.Length-2) }
  return $raw | ConvertFrom-Json
}

$runRoots = @(
  (Join-Path $Repo "ml-data\local-counterfactual-policy-v3\runs"),
  (Join-Path $Repo "ml-data\local-counterfactual-policy\runs"),
  (Join-Path $Repo "ml-data\local-policy\runs")
)
$latest = $runRoots |
  Where-Object { Test-Path $_ } |
  ForEach-Object { Get-ChildItem $_ -Directory -ErrorAction SilentlyContinue } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1
if(-not $latest){Write-Host "No policy cycles found."; exit 0}
Write-Host "========== MONSTERMAZE POLICY CYCLE REPORT =========="
Write-Host "Cycle: $($latest.Name)"

$candidate=Join-Path $latest.FullName "policy-model-candidate.json"
if(Test-Path $candidate){
  $m=Read-PolicyJson $candidate
  Write-Host ""
  Write-Host "========== MODEL =========="
  Write-Host "objective=$($m.objective)"
  Write-Host "rows=$($m.metrics.rows)"
  Write-Host "episodes=$($m.metrics.episodes)"
  Write-Host "train_rows=$($m.metrics.train_rows)"
  Write-Host "validation_rows=$($m.metrics.validation_rows)"
  Write-Host ("train_mae={0:N3}" -f $m.metrics.train_mae)
  Write-Host ("validation_mae={0:N3}" -f $m.metrics.validation_mae)
  if($null -ne $m.metrics.validation_pairwise_accuracy){
    Write-Host ("validation_pairwise_accuracy={0:P1}" -f $m.metrics.validation_pairwise_accuracy)
    Write-Host ("validation_top1_accuracy={0:P1}" -f $m.metrics.validation_top1_accuracy)
    Write-Host ("validation_decision_points={0}" -f $m.metrics.validation_decision_points)
  }
}

Write-Host ""
Write-Host "========== DATA SIGNAL =========="
$window=Join-Path $latest.FullName "training-window.jsonl"
if(Test-Path $window){
  $rows=@(
    Get-Content $window -Encoding utf8 |
      Where-Object {$_.Trim().Length -gt 0} |
      ForEach-Object { $_ | ConvertFrom-Json }
  )
  $groups=@{}
  foreach($row in $rows){
    $key="$($row.episode)|$($row.t)"
    if(-not $groups.ContainsKey($key)){ $groups[$key]=@() }
    $groups[$key] += $row
  }

  $spreads=@()
  $advantages=@()
  $baselineBest=0
  $nonBaselineBest=0
  $tieCount=0
  $candidateCounts=@()

  foreach($group in $groups.Values){
    if($group.Count -lt 2){continue}
    $candidateCounts += $group.Count
    $targets=@($group | ForEach-Object {[double]$_.target_return})
    $best=($targets | Measure-Object -Maximum).Maximum
    $baseline=[double]$group[0].target_return
    $spreads += (($targets | Measure-Object -Maximum).Maximum - ($targets | Measure-Object -Minimum).Minimum)
    $advantages += ($best - $baseline)
    $bestRows=@($group | Where-Object {[double]$_.target_return -eq [double]$best})
    if($bestRows.Count -gt 1){$tieCount++}
    if([double]$baseline -eq [double]$best){$baselineBest++}else{$nonBaselineBest++}
  }

  if($candidateCounts.Count -gt 0){
    $spreadAvg=($spreads | Measure-Object -Average).Average
    $advAvg=($advantages | Measure-Object -Average).Average
    $candAvg=($candidateCounts | Measure-Object -Average).Average
    $candMin=($candidateCounts | Measure-Object -Minimum).Minimum
    $candMax=($candidateCounts | Measure-Object -Maximum).Maximum
    Write-Host "decision_points=$($candidateCounts.Count)"
    Write-Host "rows=$($rows.Count)"
    Write-Host ("candidates_avg={0:N1} min={1} max={2}" -f $candAvg,$candMin,$candMax)
    Write-Host ("mean_target_spread={0:N3}" -f $spreadAvg)
    Write-Host ("mean_oracle_advantage_over_baseline={0:N3}" -f $advAvg)
    Write-Host ("baseline_oracle_top1={0:P1}" -f ($baselineBest / $candidateCounts.Count))
    Write-Host ("nonbaseline_oracle_best={0:P1}" -f ($nonBaselineBest / $candidateCounts.Count))
    Write-Host ("multiway_best_ties={0:P1}" -f ($tieCount / $candidateCounts.Count))
  } else {
    Write-Host "No multi-candidate decision points found."
  }
} else {
  Write-Host "No training-window.jsonl found."
}

Write-Host ""
Write-Host "========== HOLDOUTS =========="
$reports=@(Get-ChildItem $latest.FullName -Filter "policy-holdout-seed-*.json" -ErrorAction SilentlyContinue | Sort-Object Name)
if($reports.Count -eq 0){
  Write-Host "No policy holdout reports found."
} else {
  foreach($file in $reports){
    $g=Read-PolicyJson $file.FullName
    Write-Host ("seed={0} cases={1} baseline={2:N2} incumbent={3:N2} candidate={4:N2} peaks={5}/{6}/{7} improved={8} worsened={9} belowBaseline={10} passed={11}" -f
      $g.seed,$g.cases,$g.baselineAvg,$g.incumbentAvg,$g.candidateAvg,
      $g.baselinePeak,$g.incumbentPeak,$g.candidatePeak,
      $g.improved,$g.worsened,$g.belowBaseline,$g.passed)
  }
}

Write-Host ""
Write-Host "========== RUNTIME =========="
$logs=@(Get-ChildItem $latest.FullName -Filter "explore-*.log" -ErrorAction SilentlyContinue)
foreach($log in $logs){
  $lines=Get-Content $log.FullName
  $speed=($lines | Where-Object {$_ -match "SPEED_FULL_RUN"}).Count
  $modern=($lines | Where-Object {$_ -match "MODERN_FULL_RUN"}).Count
  $policy=($lines | Where-Object {$_ -match "POLICY_SELECT"}).Count
  $changed=($lines | Where-Object {$_ -match "POLICY_SELECT.*changed=True"}).Count
  Write-Host "$($log.Name): speedCases=$speed modernCases=$modern policySelections=$policy policyChanges=$changed"
}
$dataFiles=@(Get-ChildItem $latest.FullName -Filter "explore-*.jsonl" -ErrorAction SilentlyContinue)
$totalBytes=0
foreach($dataFile in $dataFiles){$totalBytes += $dataFile.Length}
Write-Host "counterfactualFiles=$($dataFiles.Count) counterfactualBytes=$totalBytes"

Write-Host ""
Write-Host "========== STATUS =========="
$counterfactualCurrent = Test-Path (Join-Path $Repo "ml-data\local-counterfactual-policy-v3\current\policy-model.json")
  -or Test-Path (Join-Path $Repo "ml-data\local-counterfactual-policy\current\policy-model.json")
$legacyCurrent = Test-Path (Join-Path $Repo "ml-data\local-policy\current\policy-model.json")
Write-Host "candidatePromoted=$($counterfactualCurrent -or $legacyCurrent)"
Write-Host "========== END REPORT =========="
