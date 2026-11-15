$root = "D:\PROJELER\cloudstream\BTVault"
$readme = Get-Content -LiteralPath (Join-Path $root "README.md") -Encoding UTF8 -Raw

$rows = @{}
foreach ($m in [regex]::Matches($readme, '<tr><td>[^<]*<strong>([^<]+)</strong></td><td>[^<]*</td><td>([^<]*)</td></tr>')) {
    $rows[$m.Groups[1].Value] = $m.Groups[2].Value
}

$bad = 0
Get-ChildItem -Path $root -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'build.gradle.kts') } | Sort-Object Name | ForEach-Object {
    $c = Get-Content -LiteralPath (Join-Path $_.FullName 'build.gradle.kts') -Raw
    $status = if ($c -match '(?m)^\s*status\s*=\s*(\d+)') { [int]$matches[1] } else { -1 }
    if (-not $rows.ContainsKey($_.Name)) { "EKSIK SATIR: $($_.Name)"; $bad++; return }
    $cell = $rows[$_.Name]
    $isClosed = $cell -match 'Kapali'
    $expectClosed = ($status -eq 0)
    if ($isClosed -ne $expectClosed) { "UYUMSUZ: $($_.Name) status=$status hucre='$cell'"; $bad++ }
}
"Satir sayisi: $($rows.Count) | Uyumsuzluk: $bad"
