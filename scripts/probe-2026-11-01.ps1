$ErrorActionPreference = "SilentlyContinue"
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$outDir = Join-Path $PSScriptRoot "probe-2026-11-01"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$targets = [ordered]@{
    "DiziMag"        = "https://dizimag.onl"
    "HDFilmCehennemi2" = "https://www.hdfilmcehennemi2.biz"
    "TvDiziler"      = "https://tvdiziler.tv"
    "YabanciDizi"    = "https://yabancidizi.news"
    "IzleAI"         = "https://selcukflix.app"
    "JetFilmizle"    = "https://jetfilmizle.app"
    "4KFilmIzlesene" = "https://4kfilmizlesene.xyz"
    "HDFilmSitesi"   = "https://dizifilmizle.to"
    "DiziMom"        = "https://www.dizidad.com"
    "SetFilmIzle"    = "https://www.setfilmizle.nl"
}

foreach ($k in $targets.Keys) {
    $url = $targets[$k]
    $u = [uri]$url; $h = $u.Host; $port = 443; if ($u.Scheme -eq 'http') { $port = 80 }
    $ip = (Resolve-DnsName $h -Server 8.8.8.8 -Type A -ErrorAction SilentlyContinue | Where-Object IPAddress | Select-Object -First 1).IPAddress
    if (-not $ip) { "$k NXDOMAIN"; continue }
    $f = Join-Path $outDir ($k + ".html")
    $r = curl.exe -s -o $f -w "%{http_code}|%{url_effective}|%{size_download}" --resolve "${h}:${port}:$ip" -A $ua -L --max-time 30 --compressed $url 2>$null
    "$k $r"
}
