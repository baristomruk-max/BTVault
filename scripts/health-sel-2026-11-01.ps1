# Selector check against health-2026-11-01 captured homepages.
$ErrorActionPreference = "SilentlyContinue"
$root = Split-Path $PSScriptRoot -Parent
$htmlDir = Join-Path $PSScriptRoot "health-2026-11-01"

$rows = @()
foreach ($d in (Get-ChildItem $root -Directory | Sort-Object Name)) {
    $kt = Get-ChildItem (Join-Path $d.FullName "src\main\kotlin") -Recurse -Filter *.kt -ErrorAction SilentlyContinue |
          Where-Object { $_.BaseName -eq $d.Name } | Select-Object -First 1
    if (-not $kt) { continue }
    $code = [System.IO.File]::ReadAllText($kt.FullName)
    $tokens = New-Object System.Collections.Generic.List[string]
    foreach ($m in [regex]::Matches($code, '(?:select|selectFirst|getElementsByClass|getElementsByTag)\(\s*"([^"]+)"')) {
        $sel = $m.Groups[1].Value
        foreach ($c in [regex]::Matches($sel, '\.([A-Za-z0-9_-]+)')) { $tokens.Add($c.Groups[1].Value) }
        foreach ($i in [regex]::Matches($sel, '#([A-Za-z0-9_-]+)'))  { $tokens.Add($i.Groups[1].Value) }
        foreach ($t in [regex]::Matches($sel, '(?:^|[\s>+~(])([a-z][a-z0-9]{2,})')) { $tokens.Add($t.Groups[1].Value) }
    }
    $tokens = @($tokens | Sort-Object -Unique)
    $htmlPath = Join-Path $htmlDir ($d.Name + ".html")
    $html = ""
    if (Test-Path $htmlPath) { $html = [System.IO.File]::ReadAllText($htmlPath) }
    if ($tokens.Count -eq 0) { continue }
    if ($html.Length -eq 0) {
        $rows += [pscustomobject]@{ Provider=$d.Name; Tokens=$tokens.Count; Found=0; Missing="(no html)" }
        continue
    }
    $found = 0; $missing = @()
    foreach ($t in $tokens) {
        if ($html -match ('(?<![\w-])' + [regex]::Escape($t) + '(?![\w-])')) { $found++ } else { $missing += $t }
    }
    $rows += [pscustomobject]@{ Provider=$d.Name; Tokens=$tokens.Count; Found=$found; Missing=($missing -join ",") }
}
$rows | Sort-Object Provider | Format-Table -AutoSize | Out-String -Width 250 | Write-Output
$rows | Export-Csv -Path (Join-Path $PSScriptRoot "health-sel-2026-11-01.csv") -NoTypeInformation -Encoding UTF8
