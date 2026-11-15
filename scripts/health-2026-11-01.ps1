# Live reachability + structure check for every BTVault provider, 2026-11-01.
# ASCII only (PowerShell 5 reads .ps1 as ANSI).
$ErrorActionPreference = "SilentlyContinue"
$root = Split-Path $PSScriptRoot -Parent
$outDir = Join-Path $PSScriptRoot "health-2026-11-01"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

function Resolve-Ip([string]$h) {
    try { return (Resolve-DnsName $h -Server 8.8.8.8 -Type A -ErrorAction Stop | Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress } catch { return $null }
}

$rows = @()

foreach ($d in (Get-ChildItem $root -Directory | Sort-Object Name)) {
    $kt = Get-ChildItem (Join-Path $d.FullName "src\main\kotlin") -Recurse -Filter *.kt -ErrorAction SilentlyContinue |
          Where-Object { $_.BaseName -eq $d.Name } | Select-Object -First 1
    if (-not $kt) { continue }
    $t = [System.IO.File]::ReadAllText($kt.FullName)
    if ($t -notmatch 'override var mainUrl\s*=\s*"([^"]+)"') { continue }
    $url = $Matches[1]

    $row = [pscustomobject]@{ Provider = $d.Name; MainUrl = $url; DNS = ""; Status = ""; Final = ""; Size = 0; Title = ""; Note = ""; Selectors = "" }

    if ($url -notlike "http*") { $row.Status = "NOT_HTTP"; $rows += $row; continue }

    $h = ""; $port = 443
    try { $u = [uri]$url; $h = $u.Host; if ($u.Scheme -eq "http") { $port = 80 } } catch { }
    if (-not $h) { $row.Status = "BAD_URL"; $rows += $row; continue }
    $ip = Resolve-Ip $h
    if (-not $ip) { $row.Status = "NXDOMAIN"; $rows += $row; continue }
    $row.DNS = $ip

    $body = Join-Path $outDir ($d.Name + ".html")
    $fmt = "%{http_code}|%{url_effective}|%{size_download}"
    $resp = curl.exe -s -o $body -w $fmt --resolve "${h}:${port}:$ip" -A $ua -L --max-time 25 --compressed "$url" 2>$null
    if ($resp) {
        $p = "$resp".Split("|")
        $row.Status = $p[0]; $row.Final = $p[1]
        $sz = 0; [void][int]::TryParse($p[2], [ref]$sz); $row.Size = $sz
        if (Test-Path $body) {
            $html = [System.IO.File]::ReadAllText($body)
            if ($html -match '<title[^>]*>([\s\S]{0,140}?)</title>') { $row.Title = ($Matches[1] -replace '\s+', ' ').Trim() }
            if ($row.Title -match 'Just a moment') { $row.Note = "CF-CHALLENGE" }
            elseif ($row.Title -match 'Domain (is )?for sale|Buy this domain|parked|satilik|sat.n') { $row.Note = "PARKED" }
            elseif ($row.Title -match 'Engelli Web|BTK') { $row.Note = "ISP-BLOCK" }
            elseif ($row.Title -match 'Veda \(2010 - 2026\)|Domain Portf') { $row.Note = "PARKED" }
            elseif ($row.Status -eq "404") { $row.Note = "404" }
            elseif ($sz -lt 700 -and $row.Status -eq "200") { $row.Note = "EMPTY" }
        }
    } else { $row.Status = "CURL_ERROR" }
    $rows += $row
}

$rows | Sort-Object Provider | Format-Table Provider,Status,Size,Note,Title -AutoSize | Out-String -Width 220 | Write-Output
$rows | Export-Csv -Path (Join-Path $PSScriptRoot "health-2026-11-01.csv") -NoTypeInformation -Encoding UTF8
