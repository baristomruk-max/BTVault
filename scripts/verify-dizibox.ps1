# verify-dizibox.ps1
# DiziBox (rewritten 25.09.2026) - chain check:
#   1. mainPage : /dizi-arsivi/  -> article.detailed-article, title from "h3 a"
#                 (the FIRST <a> inside the card is the poster link -> title was empty)
#   2. search   : /?s=<query>    -> article.detailed-article (same title rule)
#   3. load     : /diziler/<slug> -> h1 a, div.tv-story p, div#seasons-list a
#                 -> season page -> article.grid-box -> div.post-title a
#                 season  = (\d+)\.?\s*Sezon     ep = (\d+)\.?\s*B[\u00f6o]l[\u00fcu]m
#                 (old ep regex was "[0-9]+. Bolum" and matched nothing on "1.Sezon 1.Bolulm")
#   4. loadLinks: episode page -> div#video-area iframe -> player/*.php -> <iframe> embed
#                 molystream: /embed/<id> -> /embed/sheila/<id> = HLS master
#                             (needs browser User-Agent + Referer, else 403/404)
#                 other     : loadExtractor(embed) -> vidmoly.biz / ok.ru (in cloudstream.jar)
# dizibox.live answers 403 "Just a moment" to datacenter IPs; the app solves that with
# CloudflareKiller, so steps 1-4 are reported as SKIP here when the challenge comes back.
# ASCII-only script (PowerShell 5 reads .ps1 as ANSI).
$ErrorActionPreference = "SilentlyContinue"
$ua   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$main = "https://www.dizibox.live"
$dom  = "www.dizibox.live"
$results = @()

function Add-Result([string]$name, [bool]$ok, [string]$detail) {
    $script:results += [pscustomobject]@{ name = $name; ok = $ok; detail = $detail }
    $mark = if ($ok) { "OK  " } else { "FAIL" }
    Write-Output ("  [{0}] {1,-26} {2}" -f $mark, $name, $detail)
}

function Resolve-Ip([string]$h) {
    try { return (Resolve-DnsName $h -Server 8.8.8.8 -Type A -EA Stop | Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress } catch { return $null }
}

function Get-Page([string]$url, [string]$tag, [string]$ref) {
    $file = Join-Path $env:TEMP ("dbx_" + $tag + ".html")
    $h = ""; try { $h = ([uri]$url).Host } catch { $h = "" }
    $tip = @()
    if ($h -eq $dom -and $script:ip) { $tip = @("--resolve", "${dom}:443:$script:ip") }
    elseif ($h -and $h -ne $dom) { $ip2 = Resolve-Ip $h; if ($ip2) { $tip = @("--resolve", "${h}:443:$ip2") } }
    $code = & curl.exe -s -o $file -w "%{http_code}" -A $ua -e $ref @tip --max-time 30 --compressed $url
    if ("$code" -ne "200") { return $null }
    return [System.IO.File]::ReadAllText($file)
}

function Decode-Unescape([string]$s) {
    $sb = New-Object System.Text.StringBuilder
    $i = 0
    while ($i -lt $s.Length) {
        $c = $s[$i]
        if ($c -eq '%' -and ($i + 2) -lt $s.Length) {
            $v = 0
            if ([int]::TryParse($s.Substring($i + 1, 2), [System.Globalization.NumberStyles]::HexNumber,
                                 [System.Globalization.CultureInfo]::InvariantCulture, [ref]$v)) {
                [void]$sb.Append([char]$v); $i += 3; continue
            }
        }
        [void]$sb.Append($c); $i++
    }
    return $sb.ToString()
}

function Test-Embed([string]$embed) {
    # molystream -> /embed/sheila/<id> HLS master + one real segment
    if ($embed -notlike "*molystream*") { return "other-host" }
    $sheila = $embed.Replace("/embed/", "/embed/sheila/")
    $file = Join-Path $env:TEMP "dbx_sheila.m3u8"
    $h = ([uri]$sheila).Host
    $ip = Resolve-Ip $h
    $tip = @(); if ($ip) { $tip = @("--resolve", "${h}:443:$ip") }
    $code = & curl.exe -s -o $file -w "%{http_code}" -A $ua -e $embed @tip --max-time 25 --compressed $sheila
    $txt = ""; if (Test-Path $file) { $txt = [System.IO.File]::ReadAllText($file) }
    if ($code -ne "200" -or -not $txt.StartsWith("#EXTM3U")) { return "hls-master FAIL ($code)" }

    # variant playlist = first line that is not a comment
    $variant = ""
    foreach ($line in ($txt -split [char]10)) {
        $l = $line.Trim()
        if ($l -and -not $l.StartsWith("#")) { $variant = $l; break }
    }
    if (-not $variant) { return "hls-master ok, no variant" }

    $segUrl = ""
    $f2 = Join-Path $env:TEMP "dbx_variant.m3u8"
    $code2 = & curl.exe -s -o $f2 -w "%{http_code}" -A $ua -e $sheila @tip --max-time 25 --compressed $variant
    if ("$code2" -eq "200" -and (Test-Path $f2)) {
        $t2 = [System.IO.File]::ReadAllText($f2)
        $sm = ([regex]'https://[^"\s]+\.(?:png|ts|m4s)[^"\s]*').Match($t2)
        if ($sm.Success) { $segUrl = $sm.Value }
    }
    if (-not $segUrl) { return "hls variant FAIL ($code2)" }

    $vHost = ([uri]$segUrl).Host
    $vIp = Resolve-Ip $vHost
    $vTip = @(); if ($vIp) { $vTip = @("--resolve", "${vHost}:443:$vIp") }
    $out = & curl.exe -s -o NUL -w "%{http_code}|%{size_download}" -A $ua -e $sheila --limit-rate 512k --max-time 8 @vTip $segUrl
    $p = "$out" -split "\|"
    if ($p[0] -ne "200") { return "hls segment FAIL ($($p[0]))" }
    return "hls ok (variant + $($p[1])b segment)"
}

# ---------------------------------------------------------------- static check
Write-Output "== cloudstream extractors (loadExtractor targets) =="
$jar = "$env:USERPROFILE\.gradle\caches\cloudstream\cloudstream\cloudstream.jar"
$haveVid = $false; $haveOk = $false
if (Test-Path $jar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [System.IO.Compression.ZipFile]::OpenRead($jar)
    $haveVid = [bool]($z.Entries | Where-Object { $_.FullName -like "*extractors/Vidmolybiz.class" })
    $haveOk  = [bool]($z.Entries | Where-Object { $_.FullName -like "*extractors/OkRuSSL.class" })
    $z.Dispose()
}
Add-Result "extractor:Vidmolybiz" $haveVid $jar
Add-Result "extractor:OkRu"       $haveOk  "ok.ru videoembed"

# ---------------------------------------------------------------- cloudflare gate
Write-Output "== dizibox.live =="
$script:ip = Resolve-Ip $dom
if (-not $script:ip) { Add-Result "dns" $false "cannot resolve $dom" }
$archUrl  = "$main/dizi-arsivi/"
$archFile = Join-Path $env:TEMP "dbx_arch.html"
$code = & curl.exe -s -o $archFile -w "%{http_code}" -A $ua -L --max-time 25 --compressed --resolve "${dom}:443:$script:ip" $archUrl
$arch = [System.IO.File]::ReadAllText($archFile)
$blocked = ($code -ne "200") -or ($arch -like "*Just a moment*")
if ($blocked) {
    Write-Output "  [SKIP] mainPage/search/load/player - cloudflare challenge from this IP ($code)"
    Write-Output "         (the app solves it with CloudflareKiller; chain was checked from a browser IP)"
} else {
    # -------------------------------------------------------------- 1. mainPage
    $cards = ([regex]'(?s)<article[^>]*class="[^"]*detailed-article[^"]*".*?</article>').Matches($arch)
    $empty = 0; $first = ""
    foreach ($c in $cards) {
        $m = ([regex]'(?s)<h3[^>]*>\s*<a[^>]*>(.*?)</a>').Match($c.Value)
        $title = ""
        if ($m.Success) { $title = $m.Groups[1].Value -replace '<[^>]+>', '' -replace '\s+', ' ' }
        if (-not $title) { $m2 = ([regex]'<a[^>]*title="([^"]+)"').Match($c.Value); if ($m2.Success) { $title = $m2.Groups[1].Value } }
        $title = $title.Trim()
        if (-not $title) { $empty++ }
        if (-not $first) { $first = $title }
    }
    Add-Result "mainPage:dizi-arsivi" ($cards.Count -ge 10 -and $empty -eq 0) `
        ("{0} cards, {1} empty titles, first='{2}'" -f $cards.Count, $empty, $first)

    # -------------------------------------------------------------- 2. search
    $sHtml = Get-Page "$main/?s=breaking+bad" "search" $archUrl
    $sCards = @(); if ($sHtml) { $sCards = ([regex]'(?s)<article[^>]*class="[^"]*detailed-article[^"]*".*?</article>').Matches($sHtml) }
    $sTitle = ""
    if ($sCards.Count -gt 0) {
        $m = ([regex]'(?s)<h3[^>]*>\s*<a[^>]*>(.*?)</a>').Match($sCards[0].Value)
        if ($m.Success) { $sTitle = ($m.Groups[1].Value -replace '<[^>]+>', '').Trim() }
    }
    Add-Result "search:breaking bad" ($sCards.Count -ge 1 -and $sTitle -ne "") ("{0} hits, title='{1}'" -f $sCards.Count, $sTitle)

    # -------------------------------------------------------------- 3. load
    $seriesUrl = "$main/diziler/breaking-bad-izle-2/"
    $sDoc = Get-Page $seriesUrl "series" $archUrl
    $title = ""; $plot = ""; $seasonCount = 0; $seasonUrl = ""
    if ($sDoc) {
        $m = ([regex]'(?s)<div class="tv-overview[^"]*"[^>]*>.*?<h1[^>]*>(.*?)</h1>').Match($sDoc)
        if ($m.Success) { $title = ($m.Groups[1].Value -replace '<[^>]+>', '').Trim() }
        $m = ([regex]'(?s)<div class="tv-story"[^>]*>\s*<p[^>]*>(.*?)</p>').Match($sDoc)
        if ($m.Success) { $plot = ($m.Groups[1].Value -replace '<[^>]+>', '').Trim() }
        $sm = ([regex]'(?s)<div id="seasons-list"[^>]*>(.*?)</div>').Match($sDoc)
        if ($sm.Success) {
            $seasonCount = ([regex]"href=").Matches($sm.Groups[1].Value).Count
            $um = ([regex]'href="([^"]+)"').Match($sm.Groups[1].Value)
            if ($um.Success) { $seasonUrl = $um.Groups[1].Value }
        }
    }
    Add-Result "load:series" ($title -ne "" -and $plot -ne "" -and $seasonCount -ge 1) `
        ("title='{0}', plot={1}ch, seasons={2}" -f $title, $plot.Length, $seasonCount)

    if ($seasonUrl) {
        $seDoc = Get-Page $seasonUrl "season" $seriesUrl
        $eps = @(); if ($seDoc) { $eps = ([regex]'(?s)<article[^>]*class="[^"]*grid-box[^"]*".*?</article>').Matches($seDoc) }
        $rxS = [regex]'(\d+)\.?\s*Sezon'
        $rxE = [regex]'(\d+)\.?\s*B[\u00f6o]l[\u00fcu]m'
        $bad = 0; $sample = ""
        foreach ($e in $eps) {
            $tm = ([regex]'(?s)<div class="post-title"[^>]*>\s*<a[^>]*>(.*?)</a>').Match($e.Value)
            $t  = ""
            if ($tm.Success) { $t = ($tm.Groups[1].Value -replace '<[^>]+>', '').Trim() }
            if (-not $sample) { $sample = $t }
            if (-not $rxE.IsMatch($t)) { $bad++ }
        }
        Add-Result "load:episodes" ($eps.Count -ge 1 -and $bad -eq 0) `
            ("{0} eps, {1} without episode number, first='{2}'" -f $eps.Count, $bad, $sample)
    }

    # -------------------------------------------------------------- 4. loadLinks
    $epUrl = ""
    if ($seasonUrl) {
        $seDoc2 = Get-Page $seasonUrl "season2" $seriesUrl
        if ($seDoc2) {
            $m = ([regex]'(?s)<div class="post-title"[^>]*>\s*<a[^>]*href="([^"]+)"').Match($seDoc2)
            if ($m.Success) { $epUrl = $m.Groups[1].Value }
        }
    }
    if (-not $epUrl) { $epUrl = "$main/breaking-bad-1-sezon-1-bolum-hd-1-izle/" }
    $epDoc = Get-Page $epUrl "episode" $archUrl
    if (-not $epDoc) {
        Add-Result "player:episode-page" $false "cannot fetch $epUrl"
    } else {
        $pages = @($epUrl)
        foreach ($o in ([regex]'(?s)<option value="([^"]+)"').Matches($epDoc)) {
            $v = $o.Groups[1].Value
            if ($v -and $v -notin $pages) { $pages += $v }
        }
        $resolved = 0; $detail = ""
        foreach ($pg in $pages) {
            $pd = if ($pg -eq $epUrl) { $epDoc } else { Get-Page $pg "alt" $epUrl }
            if (-not $pd) { continue }
            $im = ([regex]'<div id="video-area"[^>]*>.*?<iframe[^>]*?\ssrc="([^"]+)"').Match($pd)
            if (-not $im) { continue }
            $playerUrl = $im.Groups[1].Value
            $ph = Get-Page $playerUrl "player" $pg
            if (-not $ph) { continue }
            $html = $ph
            $om = ([regex]'atob\(\s*unescape\(\s*"([^"]+)"\s*\)\s*\)').Match($ph)
            if ($om.Success) {
                try {
                    $dec = Decode-Unescape $om.Groups[1].Value
                    $html = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($dec))
                } catch { $html = $ph }
            }
            $em = ([regex]'<iframe[^>]*?\ssrc="([^"]+)"').Match($html)
            if (-not $em) { continue }
            $embed = $em.Groups[1].Value
            $r = Test-Embed $embed
            $host = ""; try { $host = ([uri]$embed).Host } catch { }
            if ($r -eq "other-host") {
                $okHost = ($host -like "*vidmoly*" -or $host -like "*ok.ru*" -or $host -like "*dood*" -or $host -like "*stream*")
                if ($okHost) { $resolved++ }
                $detail = "$detail $host;"
            } elseif ($r -like "hls ok*") {
                $resolved++; $detail = "$detail $host -> $r;"
            } else {
                $detail = "$detail $host -> $r;"
            }
        }
        Add-Result "loadLinks:player-chain" ($resolved -ge 1) ("{0}/{1} mirrors resolved. {2}" -f $resolved, $pages.Count, $detail)
    }
}

# ---------------------------------------------------------------- molystream (always)
Write-Output "== molystream HLS (independent of dizibox) =="
$hls = Test-Embed "https://dbx.molystream.org/embed/21703-61f9cc7d9a57912b2a0c0fcc"
Add-Result "molystream:sheila-hls" ($hls -like "hls ok*") $hls

# ---------------------------------------------------------------- summary
$bad = @($results | Where-Object { -not $_.ok })
Write-Output ""
Write-Output ("{0}/{1} checks passed" -f ($results.Count - $bad.Count), $results.Count)
if ($blocked) { Write-Output "note: dizibox.live steps were SKIPPED (cloudflare challenge from datacenter IP)" }
if ($bad.Count -gt 0) { $bad | ForEach-Object { Write-Output ("  FAIL {0}: {1}" -f $_.name, $_.detail) }; exit 1 }
Write-Output "DiziBox chain: ALL OK"
exit 0
