$ErrorActionPreference = "SilentlyContinue"
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$outDir = Join-Path $PSScriptRoot "probe-2026-11-01"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

function DoReq([string]$label, [string]$method, [string]$url, [string[]]$extra) {
    $u = [uri]$url; $h = $u.Host; $port = 443; if ($u.Scheme -eq 'http') { $port = 80 }
    $ip = (Resolve-DnsName $h -Server 8.8.8.8 -Type A -ErrorAction SilentlyContinue | Where-Object IPAddress | Select-Object -First 1).IPAddress
    if (-not $ip) { "$label NXDOMAIN"; return }
    $f = Join-Path $outDir ($label + ".txt")
    $args = @("-s","-o",$f,"-w","%{http_code}|%{url_effective}|%{size_download}","--resolve","${h}:${port}:$ip","-A",$ua,"-L","--max-time","30","--compressed","-e","$($u.Scheme)://$h/")
    $args += $extra
    $args += $url
    $r = & curl.exe @args 2>$null
    "$label $r"
}

DoReq "s_dizimag" "POST" "https://dizimag.onl/search" @("-X","POST","-H","X-Requested-With: XMLHttpRequest","-H","Accept: application/json, text/javascript, */*; q=0.01","-H","Content-Type: application/x-www-form-urlencoded; charset=UTF-8","--data","query=matrix")
DoReq "s_hdc2" "POST" "https://www.hdfilmcehennemi2.biz/search" @("-X","POST","-H","X-Requested-With: XMLHttpRequest","--data","query=matrix")
DoReq "s_tvdiziler" "POST" "https://tvdiziler.tv/search?qr=matrix" @("-X","POST","-H","X-Requested-With: XMLHttpRequest")
DoReq "s_yabancidizi" "POST" "https://yabancidizi.news/search?qr=matrix" @("-X","POST","-H","X-Requested-With: XMLHttpRequest")
DoReq "s_jet" "GET" "https://jetfilmizle.app/arama?q=matrix" @()
DoReq "s_4k" "GET" "https://4kfilmizlesene.xyz/?s=matrix" @()
DoReq "s_izleai" "POST" "https://selcukflix.app/api/bg/searchContent?searchterm=matrix" @("-X","POST","-H","Content-Type: application/json","-H","Accept: application/json","--data","{}")
