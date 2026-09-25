# Reachability check for every BTVault provider mainUrl.
# Uses Google DNS (8.8.8.8) for resolution because the local router DNS returns a
# block/parking IP (195.175.254.2) for many streaming domains.
$ErrorActionPreference = "SilentlyContinue"
$root = Split-Path $PSScriptRoot -Parent
$outDir = Join-Path $PSScriptRoot "site-checks"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$rows = @()

foreach ($d in (Get-ChildItem $root -Directory | Sort-Object Name)) {
    $kt = Get-ChildItem (Join-Path $d.FullName "src\main\kotlin") -Recurse -Filter *.kt -ErrorAction SilentlyContinue |
          Where-Object { $_.BaseName -eq $d.Name } | Select-Object -First 1
    if (-not $kt) { continue }
    $t = [System.IO.File]::ReadAllText($kt.FullName)
    if ($t -notmatch 'override var mainUrl\s*=\s*"([^"]+)"') { continue }
    $url = $Matches[1]
    $row = [pscustomobject]@{ Provider = $d.Name; MainUrl = $url; DNS = ""; Status = ""; Final = ""; Size = 0; Title = "" }

    if ($url -notlike "http*") { $row.Status = "NOT_HTTP"; $rows += $row; continue }

    $uri = [uri]$url
    $hostName = $uri.Host
    $ip = (Resolve-DnsName $hostName -Server 8.8.8.8 -Type A -ErrorAction SilentlyContinue |
           Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress
    if (-not $ip) { $row.Status = "NXDOMAIN"; $rows += $row; continue }
    $row.DNS = $ip

    $body = Join-Path $outDir ($d.Name + ".html")
    $fmt = "%{http_code}|%{url_effective}|%{size_download}"
    $resp = curl.exe -s -o $body -w $fmt --resolve "${hostName}:443:$ip" -A $ua -L --max-time 30 --compressed "$url" 2>$null
    if ($resp) {
        $parts = $resp -split '\|', 3
        $row.Status = $parts[0]
        $row.Final   = $parts[1]
        $row.Size    = $parts[2]
        if (Test-Path $body) {
            $html = [System.IO.File]::ReadAllText($body)
            if ($html -match '<title[^>]*>([\s\S]{0,120}?)</title>') { $row.Title = ($Matches[1] -replace '\s+', ' ').Trim() }
        }
    } else { $row.Status = "CURL_ERROR" }
    $rows += $row
}

$rows | Format-Table -AutoSize | Out-String -Width 250 | Write-Output
$rows | Export-Csv -Path (Join-Path $PSScriptRoot "site-checks.csv") -NoTypeInformation -Encoding UTF8
