$ErrorActionPreference = "SilentlyContinue"
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$outDir = Join-Path $PSScriptRoot "probe-2026-11-01"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

function Fetch([string]$label, [string]$url) {
    $u = [uri]$url; $h = $u.Host; $port = 443; if ($u.Scheme -eq 'http') { $port = 80 }
    $ip = (Resolve-DnsName $h -Server 8.8.8.8 -Type A -ErrorAction SilentlyContinue | Where-Object IPAddress | Select-Object -First 1).IPAddress
    if (-not $ip) { "$label NXDOMAIN  $url"; return }
    $f = Join-Path $outDir ($label + ".html")
    $r = curl.exe -s -o $f -w "%{http_code}|%{url_effective}|%{size_download}" --resolve "${h}:${port}:$ip" -A $ua -L --max-time 25 --compressed $url 2>$null
    $t = ""
    if (Test-Path $f) { $html = [System.IO.File]::ReadAllText($f); if ($html -match '<title[^>]*>([\s\S]{0,100}?)</title>') { $t = ($Matches[1] -replace '\s+',' ').Trim() } }
    "$label $r  $url  title=$t"
}

Fetch "dm_cam"  "https://www.dizimom.cam"
Fetch "dm_help" "https://www.dizimom.help"
Fetch "dm_com"  "https://www.dizimom.com"
Fetch "dm_dad"  "https://dizidad.com"
Fetch "k4_detail" "https://4kfilmizlesene.xyz/dc-super-evciller-takimi-hd/"
Fetch "hdc2_api" "https://www.hdfilmcehennemi2.biz/api/search?q=matrix"
Fetch "hdc2_film" "https://www.hdfilmcehennemi2.biz/film/matrix"
