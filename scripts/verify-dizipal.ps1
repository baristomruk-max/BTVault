# verify-dizipal.ps1
# DiziPal (rewritten 25.09.2026) - chain check:
#   1. mainPage : /diziler/page/SAYFA/  -> div.post-item > a[href][title] (+ img[data-src])
#                  /filmler /animeler /platform/<x> /dizi-kategori/<x> /kategori/<x> = same
#   2. search   : /?s=<query>           -> div.post-item (type from href: /dizi/ or /anime/)
#   3. load     : /dizi/<slug>/         -> h1 own text, meta[og:image], meta[description],
#                  #season-options-list a[href*="sezon="] -> /?sezon=N -> div.episode-item
#                  ep title = <div class="episode-item"><a title="Dizi 1. Sezon 1. Bolum Izle">
#                  ep regex: (\d+)\.?\s*Sezon  /  (\d+)\.?\s*B[\u00f6o]l[\u00fcu]m
#                  (season switch is server side: the base page only holds one season)
#   4. load     : /<movie-slug>/        -> h1 + og:image + meta[description]
#                  + div.responsive-player iframe (movie pages embed directly)
#   5. loadLinks: /bolum/<slug>/ -> div[class~=responsive-player] iframe -> embed host page
#                  (embed needs Referer, else "referer gerekir")
#                  -> fetch('/dl?op=get_stream&view_id=..&hash=..')
#                  -> /dl with Origin: <embed origin>  (else {"error":"unauthorized"})
#                  -> {"url":"https://.../master.m3u8"}
#                  -> master + variant + segment need Origin AND Referer (else 403)
# ASCII-only script (PowerShell 5 reads .ps1 as ANSI).
$ErrorActionPreference = "SilentlyContinue"
$ua   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$main = "https://dizipal3008.com"
$dom  = "dizipal3008.com"
$results = @()

function Add-Result([string]$name, [bool]$ok, [string]$detail) {
    $script:results += [pscustomobject]@{ name = $name; ok = $ok; detail = $detail }
    $mark = if ($ok) { "OK  " } else { "FAIL" }
    Write-Output ("  [{0}] {1,-22} {2}" -f $mark, $name, $detail)
}

function Resolve-Ip([string]$h) {
    try { return (Resolve-DnsName $h -Server 8.8.8.8 -Type A -EA Stop | Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress } catch { return $null }
}

function Abs-Url([string]$u) {
    if (-not $u) { return "" }
    if ($u -like "http*") { return $u }
    if ($u -like "/*") { return $main + $u }
    return "$main/$u"
}

function Get-Page([string]$url, [string]$tag, [string]$ref) {
    $file = Join-Path $env:TEMP ("dpv_" + $tag + ".html")
    $h = ""; try { $h = ([uri]$url).Host } catch { $h = "" }
    $tip = @()
    if ($h -eq $dom -and $script:ip) { $tip = @("--resolve", "${dom}:443:$script:ip") }
    elseif ($h -and $h -ne $dom) { $ip2 = Resolve-Ip $h; if ($ip2) { $tip = @("--resolve", "${h}:443:$ip2") } }
    $code = & curl.exe -s -o $file -w "%{http_code}" -A $ua -e $ref -L @tip --max-time 30 --compressed $url
    if ("$code" -ne "200") { return $null }
    return [System.IO.File]::ReadAllText($file)
}

function Resolve-Stream([string]$embedUrl, [string]$tag) {
    # embed page (Referer required) -> fetch('/dl?...') -> JSON url -> HLS master/variant/segment
    $emb = Get-Page $embedUrl "$tag-emb" "$main/"
    if (-not $emb) { return @{ ok = $false; detail = "embed page not fetchable (referer?)" } }

    $pm = ([regex]"fetch\('([^']+)'\)").Match($emb)
    if (-not $pm.Success) { return @{ ok = $false; detail = "no fetch('..') in embed" } }
    $path = $pm.Groups[1].Value

    $origin = ""; $oHost = ""
    try { $u = [uri]$embedUrl; $origin = $u.Scheme + "://" + $u.Host; $oHost = $u.Host } catch { }
    if (-not $origin) { return @{ ok = $false; detail = "no origin for $embedUrl" } }

    # system DNS answers this host with a parked Cloudflare IP -> /dl then says invalid_hash;
    # always pin the origin to the Google-DNS answer (the app uses the device resolver instead)
    $dl = if ($path -like "http*") { $path } else { $origin + $path }
    $dlHost = ""; try { $dlHost = ([uri]$dl).Host } catch { $dlHost = $oHost }
    $dlIp = Resolve-Ip $dlHost
    $dlTip = @(); if ($dlIp) { $dlTip = @("--resolve", "${dlHost}:443:$dlIp") }

    $dlFile = Join-Path $env:TEMP ($tag + "_dl.json")
    $code = & curl.exe -s -o $dlFile -w "%{http_code}" -A $ua -e $embedUrl `
        -H "Origin: $origin" -H "Accept: application/json, text/javascript, */*; q=0.01" `
        -H "X-Requested-With: XMLHttpRequest" --max-time 25 --compressed @dlTip $dl
    $json = ""; if (Test-Path $dlFile) { $json = [System.IO.File]::ReadAllText($dlFile) }
    $flat = ($json -replace '\s+', ' ')
    if ($flat.Length -gt 90) { $flat = $flat.Substring(0, 90) }
    $um = ([regex]'"url"\s*:\s*"([^"]+)"').Match($json)
    if ("$code" -ne "200" -or -not $um.Success) {
        return @{ ok = $false; detail = "/dl -> $code $flat" }
    }
    $stream = $um.Groups[1].Value

    $sh = ""; try { $sh = ([uri]$stream).Host } catch { }
    $sip = Resolve-Ip $sh
    $stip = @(); if ($sip) { $stip = @("--resolve", "${sh}:443:$sip") }

    $mFile = Join-Path $env:TEMP ($tag + "_master.m3u8")
    $mc = & curl.exe -s -o $mFile -w "%{http_code}" -A $ua -e $embedUrl -H "Origin: $origin" @stip --max-time 25 $stream
    $mTxt = ""; if (Test-Path $mFile) { $mTxt = [System.IO.File]::ReadAllText($mFile) }
    if ("$mc" -ne "200" -or -not $mTxt.StartsWith("#EXTM3U")) { return @{ ok = $false; detail = "master $mc" } }

    $variant = ""
    foreach ($line in ($mTxt -split [char]10)) {
        $l = $line.Trim()
        if ($l -and -not $l.StartsWith("#")) { $variant = $l; break }
    }
    if (-not $variant) { return @{ ok = $false; detail = "no variant in master" } }

    $vFile = Join-Path $env:TEMP ($tag + "_variant.m3u8")
    $vc = & curl.exe -s -o $vFile -w "%{http_code}" -A $ua -e $embedUrl -H "Origin: $origin" @stip --max-time 25 $variant
    $segUrl = ""
    if ("$vc" -eq "200" -and (Test-Path $vFile)) {
        $vTxt = [System.IO.File]::ReadAllText($vFile)
        $sm = ([regex]'\S+\.ts\S*').Match($vTxt)
        if ($sm.Success) { $segUrl = $sm.Value }
    }
    if (-not $segUrl) { return @{ ok = $false; detail = "variant $vc, no segment" } }

    $sgH = ""; try { $sgH = ([uri]$segUrl).Host } catch { }
    $sgIp = Resolve-Ip $sgH
    $sgTip = @(); if ($sgIp) { $sgTip = @("--resolve", "${sgH}:443:$sgIp") }
    $out = & curl.exe -s -o NUL -w "%{http_code}|%{size_download}" -A $ua -e $embedUrl -H "Origin: $origin" `
        --limit-rate 512k --max-time 8 @sgTip $segUrl
    $p = "$out" -split "\|"
    if ($p[0] -ne "200") { return @{ ok = $false; detail = "segment $($p[0])" } }
    return @{ ok = $true; detail = "dl->hls ok ($($p[1])b segment from $sh)" }
}

# ---------------------------------------------------------------- build artifact
Write-Output "== DiziPal =="
$cs3 = Join-Path $PSScriptRoot "..\DiziPal\build\DiziPal.cs3"
Add-Result "build:DiziPal.cs3" (Test-Path $cs3) $cs3

$script:ip = Resolve-Ip $dom
if (-not $script:ip) { Add-Result "dns" $false "cannot resolve $dom"; exit 1 }

# ---------------------------------------------------------------- 1. mainPage
$mainFile = Join-Path $env:TEMP "dpv_main.html"
$code = & curl.exe -s -o $mainFile -w "%{http_code}" -A $ua -L --max-time 25 --compressed `
    --resolve "${dom}:443:$script:ip" "$main/diziler/page/1/"
$mh = [System.IO.File]::ReadAllText($mainFile)
$cardCount = ([regex]'<div class="post-item">').Matches($mh).Count
$first = ""; $empty = 0
foreach ($tm in ([regex]'(?s)<div class="post-item">\s*<a href="([^"]+)" title="([^"]*)"').Matches($mh)) {
    $v = $tm.Groups[2].Value.Trim()
    if (-not $v) { $empty++ } elseif (-not $first) { $first = $v }
}
Add-Result "mainPage:diziler" ($cardCount -ge 10 -and $empty -eq 0 -and $first -ne "") `
    ("{0} cards, {1} empty titles, first='{2}', http={3}" -f $cardCount, $empty, $first, $code)

# ---------------------------------------------------------------- 2. search
$sHtml = Get-Page "$main/?s=the+sopranos" "search" "$main/"
$sCount = 0; $sTitle = ""; $seriesUrl = ""
if ($sHtml) {
    $sCount = ([regex]'<div class="post-item">').Matches($sHtml).Count
    $m = ([regex]'(?s)<div class="post-item">\s*<a href="([^"]+)" title="([^"]*)"').Match($sHtml)
    if ($m.Success) { $sTitle = $m.Groups[2].Value.Trim() }
    $sm = ([regex]'(?s)<div class="post-item">\s*<a href="([^"]*/dizi/[^"]+)"').Match($sHtml)
    if ($sm.Success) { $seriesUrl = $sm.Groups[1].Value }
}
Add-Result "search:the sopranos" ($sCount -ge 1 -and $sTitle -ne "") `
    ("{0} hits, first='{1}'" -f $sCount, $sTitle)

# ---------------------------------------------------------------- 3. load (series)
if (-not $seriesUrl) { $seriesUrl = "$main/dizi/the-sopranos/" }
$sDoc = Get-Page $seriesUrl "series" "$main/"
$title = ""; $plot = ""; $poster = ""; $seasonCount = 0; $seasonUrl = ""; $seDoc = $null
if ($sDoc) {
    $hm = ([regex]'(?s)<h1[^>]*>(.*?)</h1>').Match($sDoc)
    if ($hm.Success) { $title = ($hm.Groups[1].Value -replace '<[^>]+>', ' ' -replace '\s+', ' ').Trim() }
    $dm = ([regex]'<meta name="description" content="([^"]+)"').Match($sDoc)
    if ($dm.Success) { $plot = $dm.Groups[1].Value }
    $om = ([regex]'<meta property="og:image" content="([^"]+)"').Match($sDoc)
    if ($om.Success) { $poster = $om.Groups[1].Value }
    $lm = ([regex]'id="season-options-list"[\s\S]{0,600}?href="([^"]+)"').Match($sDoc)
    if ($lm.Success) { $seasonUrl = Abs-Url $lm.Groups[1].Value }
    $seasonCount = ([regex]'\?sezon=\d+').Matches($sDoc).Count
}
Add-Result "load:series" ($title -ne "" -and $plot -ne "" -and $poster -ne "" -and $seasonCount -ge 1) `
    ("title='{0}', plot={1}ch, poster={2}, seasons={3}" -f $title, $plot.Length, $(if ($poster) { "yes" } else { "no" }), $seasonCount)

# season switch is server side - fetch one season and check the episode titles there
$epSample = ""; $epUrl = ""
if ($seasonUrl) {
    $seDoc = Get-Page $seasonUrl "season" $seriesUrl
    $rxS = [regex]'(\d+)\.?\s*Sezon'
    $rxE = [regex]'(\d+)\.?\s*B[\u00f6o]l[\u00fcu]m'
    $eps = 0; $bad = 0
    if ($seDoc) {
        foreach ($tm in ([regex]'(?s)<div class="episode-item">\s*<a href="([^"]+)" title="([^"]+)"').Matches($seDoc)) {
            $eps++
            $t = $tm.Groups[2].Value
            if (-not $epSample) { $epSample = $t; $epUrl = Abs-Url $tm.Groups[1].Value }
            if (-not $rxE.IsMatch($t)) { $bad++ }
            if (-not $rxS.IsMatch($t)) { $bad++ }
        }
    }
    Add-Result "load:season-eps" ($eps -ge 1 -and $bad -eq 0) `
        ("{0} eps, {1} title regex misses, first='{2}'" -f $eps, $bad, $epSample)
} else {
    Add-Result "load:season-eps" $false "no #season-options-list link on $seriesUrl"
}

# ---------------------------------------------------------------- 4. load (movie)
$block = @("diziler", "filmler", "animeler", "kategori", "dizi-kategori", "platform", "sayfa",
           "bolum", "dizi", "anime", "feed", "giris", "iletisim", "etiket", "yazar")
$movieSlug = ""
if ($sHtml) {
    # single-segment href taken from a search CARD (wp-feed links like /feed/ are not cards)
    foreach ($hm in ([regex]'(?s)<div class="post-item">\s*<a href="https://[^"/]+/([a-z0-9\-]+)/"').Matches($sHtml)) {
        $seg = $hm.Groups[1].Value
        if ($seg -and $block -notcontains $seg) { $movieSlug = $seg; break }
    }
}
if (-not $movieSlug) {
    # "the sopranos" search only returns the series -> take a movie from the film list
    $fDoc = Get-Page "$main/filmler/page/1/" "films" "$main/"
    foreach ($hm in ([regex]'(?s)<div class="post-item">\s*<a href="https://[^"/]+/([a-z0-9\-]+)/"').Matches("$fDoc")) {
        $seg = $hm.Groups[1].Value
        if ($seg -and $block -notcontains $seg) { $movieSlug = $seg; break }
    }
}
if (-not $movieSlug) { $movieSlug = "the-love-hypothesis" }
$mvDoc = Get-Page "$main/$movieSlug/" "movie" "$main/"
$mTitle = ""; $mPlot = ""; $mPoster = ""; $mPlayer = $false
if ($mvDoc) {
    $hm = ([regex]'(?s)<h1[^>]*>(.*?)</h1>').Match($mvDoc)
    if ($hm.Success) { $mTitle = ($hm.Groups[1].Value -replace '<[^>]+>', ' ' -replace '\s+', ' ').Trim() }
    $dm = ([regex]'<meta name="description" content="([^"]+)"').Match($mvDoc)
    if ($dm.Success) { $mPlot = $dm.Groups[1].Value }
    $om = ([regex]'<meta property="og:image" content="([^"]+)"').Match($mvDoc)
    if ($om.Success) { $mPoster = $om.Groups[1].Value }
    $mPlayer = ([regex]'<div class="[^"]*responsive-player[^"]*"').IsMatch($mvDoc)
}
Add-Result "load:movie" ($mTitle -ne "" -and $mPlot -ne "" -and $mPoster -ne "" -and $mPlayer) `
    ("slug={0}, title='{1}', plot={2}ch, player={3}" -f $movieSlug, $mTitle, $mPlot.Length, $mPlayer)

# ---------------------------------------------------------------- 5. loadLinks
$playerPage = ""; $playerTag = ""
if ($epUrl) { $playerPage = Get-Page $epUrl "episode" $seriesUrl; $playerTag = "episode" }
if (-not $playerPage) { $playerPage = $mvDoc; $playerTag = "movie" }

$embedUrl = ""
if ($playerPage) {
    $im = ([regex]'(?s)<div class="[^"]*responsive-player[^"]*"[^>]*>\s*<iframe[^>]*?\ssrc="([^"]+)"').Match($playerPage)
    if (-not $im.Success) { $im = ([regex]'<iframe[^>]*?\ssrc="([^"]+)"').Match($playerPage) }
    if ($im.Success) { $embedUrl = Abs-Url $im.Groups[1].Value }
}
if (-not $embedUrl) {
    Add-Result "player:embed-dl" $false "no responsive-player iframe on $playerTag page"
} else {
    $r = Resolve-Stream $embedUrl $playerTag
    Add-Result "player:embed-dl" ([bool]$r.ok) ("{0} {1}" -f $embedUrl, $r.detail)
}

# ---------------------------------------------------------------- summary
$bad = @($results | Where-Object { -not $_.ok })
Write-Output ""
Write-Output ("{0}/{1} checks passed" -f ($results.Count - $bad.Count), $results.Count)
if ($bad.Count -gt 0) { $bad | ForEach-Object { Write-Output ("  FAIL {0}: {1}" -f $_.name, $_.detail) }; exit 1 }
Write-Output "DiziPal chain: ALL OK"
exit 0
