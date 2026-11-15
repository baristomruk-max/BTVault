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
    "$label $r title=$t"
}

Fetch "d_dizimag"  "https://dizimag.onl/dizi/star-city/sezon-1/bolum-8"
Fetch "d_dizimag_film" "https://dizimag.onl/film/matrix"
Fetch "d_yabanci"  "https://yabancidizi.news/dizi/lupin-izle-1/sezon-4"
Fetch "d_jet"      "https://jetfilmizle.app/film/gece-kopekleri"
Fetch "d_4k"       "https://4kfilmizlesene.xyz/minyonlar-2-grunun-yukselisi/"
Fetch "d_hdfs"     "https://dizifilmizle.to/film/predator-vahsi-topraklar-izle14"
Fetch "d_hdc2"     "https://www.hdfilmcehennemi2.biz/matrix-1-izle"
Fetch "s_tvdiziler2" "https://tvdiziler.tv/search?qr=gibi"
Fetch "d_tvdiziler" "https://tvdiziler.tv/dizi/gibi-izle"
Fetch "d_filmkovasi" "https://filmkovasi.com/"
