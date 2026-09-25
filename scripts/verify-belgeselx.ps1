# verify-belgeselx.ps1
# belgeselx.com (redesigned 2026) - full chain check after the BelgeselX rewrite:
#   1. mainPage : /konu/<slug> -> a.px-card, page 2 = /ajax_konukat.php?url=<slug>&page=2
#   2. search   : Google CSE (cx 016376594590146270301:iwmy65ijgrm) -> /belgeseldizi/ urls
#   3. load     : /belgeseldizi/<slug> -> px-hero-title + px-ep-card (butonKaydet('id'))
#   4. loadLinks: /belgesel/<slug> -> diziGetir(...) -> /video/data/<map>.php?id=&sira=
#                 -> jwplayer file:"<mp4>" (label) or <iframe> fallback
# ASCII-only script (PowerShell 5 reads .ps1 as ANSI).
$ErrorActionPreference = "SilentlyContinue"
$ua   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$main = "https://belgeselx.com"
$dom  = "belgeselx.com"
$results = @()

function Resolve-Ip([string]$h) {
    try { return (Resolve-DnsName $h -Server 8.8.8.8 -Type A -EA Stop | Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress } catch { return $null }
}

function Get-Page([string]$url, [string]$tag, [string]$ref) {
    $file = Join-Path $env:TEMP ("blx_" + $tag + ".html")
    $h = ""; try { $h = ([uri]$url).Host } catch { $h = "" }
    $tip = if ($h -eq $dom -and $script:ip) { @("--resolve", "${dom}:443:$script:ip") } else { @() }
    $code = & curl.exe -s -o $file -w "%{http_code}" -A $ua -e $ref @tip --max-time 30 --compressed $url
    if ("$code" -ne "200") { return $null }
    return [System.IO.File]::ReadAllText($file)
}

# small GET that reports status/type without pulling the whole movie
function Probe([string]$url, [string]$ref, [bool]$wantVideo) {
    $h = ""; try { $h = ([uri]$url).Host } catch { $h = "" }
    $tip = if ($h -and $h -ne $dom) {
        $ip2 = Resolve-Ip $h
        if ($ip2) { @("--resolve", "${h}:443:$ip2") } else { @() }
    } elseif ($h -eq $dom -and $script:ip) { @("--resolve", "${dom}:443:$script:ip") } else { @() }

    $out = & curl.exe -s -o NUL -w "%{http_code}|%{content_type}" -A $ua -e $ref -L `
        --limit-rate 512k --max-time 8 @tip $url
    $parts = "$out" -split "\|"
    $code  = $parts[0]; $type = ""
    if ($parts.Count -gt 1) { $type = $parts[1] }
    if ($wantVideo) { return ("$code" -eq "200" -and $type -like "video*") }
    return ("$code" -eq "200" -and $type -like "text/html*")
}

$script:ip = Resolve-Ip $dom
if (-not $script:ip) { Write-Output "FAIL dns"; exit 1 }

# ---------------------------------------------------------------- 1. mainPage
$konu = "$main/konu/hayvan-belgeselleri"
$konuHtml = Get-Page $konu "konu" $konu
$cardRx   = [regex]'<a class="px-card" href="([^"]+)"'
$c1 = @(); if ($konuHtml) { $c1 = @($cardRx.Matches($konuHtml)) }
$first1 = ""
if ($c1.Count -gt 0) { $first1 = $c1[0].Groups[1].Value }
$pages = ""
$pm = ([regex]'data-pages="(\d+)"').Matches($konuHtml)
if ($pm.Count -gt 0) { $pages = $pm[0].Groups[1].Value }

$ajaxHtml = Get-Page "$main/ajax_konukat.php?url=hayvan-belgeselleri&page=2" "ajax" $konu
$c2 = @(); if ($ajaxHtml) { $c2 = @($cardRx.Matches($ajaxHtml)) }
$first2 = ""
if ($c2.Count -gt 0) { $first2 = $c2[0].Groups[1].Value }

$mainOk = ($c1.Count -ge 1 -and $c2.Count -ge 1 -and $first1 -ne $first2 -and $first1 -like "$main/belgeseldizi/*")
$results += [pscustomobject]@{ Step="mainPage"; Ok=[bool]$mainOk; Info=("page1={0} page2={1} pages={2} first={3}" -f $c1.Count, $c2.Count, $pages, $first1.Replace($main, "")) }

# ---------------------------------------------------------------- 2. search
$cx = "016376594590146270301:iwmy65ijgrm"
$q  = [uri]::EscapeDataString("uzay")
$tokenFile = Join-Path $env:TEMP "blx_token.js"
& curl.exe -s -o $tokenFile -A $ua --max-time 30 --compressed "https://cse.google.com/cse.js?cx=$cx"
$tokenText = [System.IO.File]::ReadAllText($tokenFile)
$lib  = [regex]::Match($tokenText, 'cselibVersion":\s*"([^"]+)"').Groups[1].Value
$tok  = [regex]::Match($tokenText, 'cse_token":\s*"([^"]+)"').Groups[1].Value

$found = @()
if ($lib -and $tok) {
    $u = "https://cse.google.com/cse/element/v1?rsz=filtered_cse&num=100&hl=tr&source=gcsc&cselibv=$lib&cx=$cx&q=$q&safe=off&cse_tok=$tok&oq=$q&callback=google.search.cse.api9969&rurl=https%3A%2F%2Fbelgeselx.com%2F"
    $cseFile = Join-Path $env:TEMP "blx_cse2.json"
    & curl.exe -s -o $cseFile -A $ua --max-time 30 --compressed $u
    $json = [System.IO.File]::ReadAllText($cseFile)
    foreach ($m in [regex]::Matches($json, '"url": "([^"]+)"')) {
        $v = $m.Groups[1].Value
        if ($v -like "*/belgeseldizi/*") { $found += $v }
    }
}
$found = @($found | Sort-Object -Unique)
$searchOk = ($found.Count -gt 0)
$results += [pscustomobject]@{ Step="search"; Ok=[bool]$searchOk; Info=("cse token={0} series urls={1} e.g. {2}" -f $(if ($tok) { "ok" } else { "MISSING" }), $found.Count, $(if ($found.Count) { $found[0].Replace($main, "") } else { "-" })) }

# ---------------------------------------------------------------- 3. load
$seriesUrl = ""
if ($found.Count -gt 0) { $seriesUrl = $found[0] }
if (-not $seriesUrl -and $c1.Count -gt 0) { $seriesUrl = $c1[0].Groups[1].Value }
$detail = Get-Page $seriesUrl "detail" $seriesUrl

$title = ""; $poster = ""; $plot = ""; $tagCount = 0; $epCount = 0
$epIds = @(); $watchUrl = ""; $firstEpName = ""
if ($detail) {
    $m = [regex]::Match($detail, '<h1 class="px-hero-title">([^<]+)</h1>')
    if ($m.Success) { $title = $m.Groups[1].Value.Trim() }
    $m = [regex]::Match($detail, '<div class="px-dizi-card-poster">\s*<img src="([^"]+)"')
    if ($m.Success) { $poster = $m.Groups[1].Value }
    $m = [regex]::Match($detail, 'name="description" content="([^"]+)"')
    if ($m.Success) { $plot = $m.Groups[1].Value }
    $tagCount = ([regex]::Matches($detail, 'class="px-imdb-genre-tag"')).Count

    foreach ($m in [regex]::Matches($detail, '<a[^>]*class="px-ep-card[^"]*"[^>]*href="([^"]+)"[^>]*onclick="butonKaydet\(''(\d+)''\)"')) {
        if (-not $watchUrl) { $watchUrl = $m.Groups[1].Value }
        $epIds += $m.Groups[2].Value
    }
    $m = [regex]::Match($detail, '<span class="px-ep-title">([^<]+)</span>')
    if ($m.Success) { $firstEpName = $m.Groups[1].Value.Trim() }
    $epCount = ([regex]::Matches($detail, 'class="px-ep-card')).Count
}
$loadOk = ($title -and $poster -and $plot -and $epCount -gt 0 -and $epIds.Count -gt 0 -and $watchUrl)
$results += [pscustomobject]@{ Step="load"; Ok=[bool]$loadOk; Info=("{0} | poster={1} plot={2} tags={3} eps={4} ids={5}" -f $title, $(if ($poster) { "y" } else { "n" }), $(if ($plot.Length -gt 40) { ("{0}ch" -f $plot.Length) } else { "SHORT" }), $tagCount, $epCount, $epIds.Count) }

# ---------------------------------------------------------------- 4. loadLinks
$srcMap = @{ "0" = "new5"; "2" = "new1"; "5" = "new4"; "3" = "new2"; "4" = "new3" }
$getRx  = [regex]"diziGetir\(\s*'(\d+)'\s*,\s*'(\d+)'\s*,\s*'(\d+)'\s*,\s*'(\d+)'\s*,\s*'([^']*)'\s*,\s*'[^']*'\s*,\s*'[^']*'\s*,\s*'(\d*)'\s*,\s*'(\d*)'"
$jwRx   = [regex]'file:\s*"([^"]*)"\s*,\s*label:\s*"([^"]*)"'
$ifRx   = [regex]'<iframe[^>]*\ssrc="([^"]+)"'

$streamOk = $false; $info = "(no episode)"
if ($watchUrl) {
    $watchHtml = Get-Page $watchUrl "watch" $watchUrl
    $calls = @(); if ($watchHtml) { $calls = @($getRx.Matches($watchHtml)) }

    $direct = @(); $frames = @(); $tried = 0
    foreach ($call in $calls) {
        if ($tried -ge 3) { break }
        $epid = $call.Groups[1].Value
        if ($epIds.Count -gt 0 -and $epIds -notcontains $epid) { continue }
        $tried++

        $ics = @($call.Groups[2].Value, $call.Groups[3].Value, $call.Groups[4].Value)
        for ($i = 0; $i -lt 3; $i++) {
            $file = $srcMap[$ics[$i]]
            if (-not $file) { $file = "default" }
            $wrap = "$main/video/data/$file.php?id=${epid}&sira=" + ($i + 1)
            $page = Get-Page $wrap "wrap_${epid}_$i" $watchUrl
            if (-not $page) { continue }

            $hitJw = $false
            foreach ($m in $jwRx.Matches($page)) {
                $link = $m.Groups[1].Value.Trim()
                if (-not $link.StartsWith("http")) { continue }
                $direct += "$link||$($m.Groups[2].Value.Trim())"
                $hitJw = $true
            }
            if ($hitJw) { continue }

            foreach ($m in $ifRx.Matches($page)) {
                $link = $m.Groups[1].Value.Trim()
                if (-not $link.StartsWith("http")) { continue }
                if ($link -like "*googlesyndication*" -or $link -like "*doubleclick*" -or $link -like "*facebook.com*") { continue }
                $frames += $link
                break
            }
        }
        if ($direct.Count -gt 0 -or $frames.Count -gt 0) { break }
    }

    $probed = @()
    # third party direct media first: the site's own /video/*.php proxies only redirect to googlevideo
    $ordered = @($direct | Select-Object -Unique | Sort-Object { if ($_ -like "*$dom/video/*") { 1 } else { 0 } })
    foreach ($d in ($ordered | Select-Object -First 4)) {
        $parts   = $d -split "\|\|"
        $link    = $parts[0]
        $label   = ""
        if ($parts.Count -gt 1) { $label = $parts[1] }
        if (Probe $link $watchUrl $true) { $probed += ("mp4:" + $label) }
    }
    foreach ($fr in ($frames | Select-Object -Unique | Select-Object -First 2)) {
        if (Probe $fr $watchUrl $false) { $probed += "iframe" }
    }
    $streamOk = ($probed.Count -gt 0)
    $info = "ep={0} direct={1} iframes={2} ok=[{3}]" -f $tried, $direct.Count, $frames.Count, ($probed -join ",")
} else {
    $info = "no watch url from detail page"
}
$results += [pscustomobject]@{ Step="loadLinks"; Ok=$streamOk; Info=$info }

""
$results | Format-Table Step,Ok,Info -AutoSize | Out-String -Width 220 | Write-Output
if (@($results | Where-Object { -not $_.Ok }).Count -gt 0) { exit 1 }
Write-Output "BelgeselX chain: ALL OK"
