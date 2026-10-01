$ErrorActionPreference="Stop"
$Repo=Split-Path -Parent $PSScriptRoot
Set-Location $Repo
$latest=Get-ChildItem .\ml-data\local-policy\runs -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if(-not $latest){Write-Host "No policy cycles found."; exit 0}
Write-Host "========== MONSTERMAZE POLICY CYCLE REPORT =========="
Write-Host "Cycle: $($latest.Name)"
$candidate=Join-Path $latest.FullName "policy-model-candidate.json"
if(Test-Path $candidate){
  $m=Get-Content $candidate -Raw | ConvertFrom-Json
  Write-Host ""
  Write-Host "========== MODEL =========="
  Write-Host "objective=$($m.objective)"
  Write-Host "rows=$($m.metrics.rows)"
  Write-Host "episodes=$($m.metrics.episodes)"
  Write-Host "train_rows=$($m.metrics.train_rows)"
  Write-Host "validation_rows=$($m.metrics.validation_rows)"
  Write-Host ("train_mae={0:N3}" -f $m.metrics.train_mae)
  Write-Host ("validation_mae={0:N3}" -f $m.metrics.validation_mae)
}
Write-Host ""
Write-Host "========== HOLDOUTS =========="
$reports=@(Get-ChildItem $latest.FullName -Filter "policy-holdout-seed-*.json" -ErrorAction SilentlyContinue | Sort-Object Name)
if($reports.Count -eq 0){Write-Host "No policy holdout reports found."}
else{
  foreach($file in $reports){
    $g=Get-Content $file.FullName -Raw | ConvertFrom-Json
    Write-Host ("seed={0} cases={1} baselineAvg={2:N2} candidateAvg={3:N2} baselinePeak={4} candidatePeak={5} improved={6} worsened={7} same={8} passed={9}" -f $g.seed,$g.cases,$g.baselineAvg,$g.candidateAvg,$g.baselinePeak,$g.candidatePeak,$g.improved,$g.worsened,$g.same,$g.passed)
  }
}
Write-Host ""
Write-Host "========== RUNTIME =========="
$logs=@(Get-ChildItem $latest.FullName -Filter "explore-*.log" -ErrorAction SilentlyContinue)
foreach($log in $logs){
  $lines=Get-Content $log.FullName
  $s=($lines | Where-Object {$_ -match "SPEED_FULL_RUN"}).Count
  $m=($lines | Where-Object {$_ -match "MODERN_FULL_RUN"}).Count
  $p=($lines | Where-Object {$_ -match "POLICY_SELECT"}).Count
  Write-Host "$($log.Name): speedCases=$s modernCases=$m policySelections=$p"
}
Write-Host ""
Write-Host "========== STATUS =========="
Write-Host "candidatePromoted=$(Test-Path (Join-Path $Repo "ml-data\local-policy\current\policy-model.json"))"
Write-Host "========== END REPORT =========="
