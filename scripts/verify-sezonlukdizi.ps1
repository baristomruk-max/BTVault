# verify-sezonlukdizi.ps1
# SezonlukDizi (fixed 25.09.2026) - chain check:
#   1. mainPage : /diziler.asp?siralama_tipi=id&s=<n>  -> div.afis a[href*='/diziler/']
#                 card = a.column > div.ui.card > div.image img[data-src] + div.description
#                 page 2 must differ from page 1; /diziler.asp?kat=N&s=1 = same card layout
#   2. search   : POST /ajax/arama.asp (q=) -> {status, results:{<kategori>:{results:[...]}}}
#                 the old diziler.asp?adi= page answers 403 to datacenter IPs -> fallback only
#   3. load     : /diziler/<slug> -> div.header (baslik), div.image img[data-src] (poster),
#                 div.extra content span (yil), span#tartismayorum-konu (konu),
#                 div.labels a[href*='tur'] (tur), span "... Dk." (sure)
#   4. load     : /bolumler/<slug> -> table.unstackable > tbody tr
#                 td(2)=sezon, td(3)=bolum link + no, td(4)=bolum adi
#                 /oyuncular/<slug> -> div.card + div.header + img (oyuncular)
#   5. loadLinks: /<slug>/<bolum>.html -> div#dilsec[data-id]
#                 POST /ajax/dataAlternatif22.asp (bid, dil=1|0) -> [{id, baslik, kalite}]
#                 POST /ajax/dataEmbed22.asp       (id)          -> <iframe src>
#                 (the old .asp endpoints returned 404 -> renamed with the "22" suffix,
#                  id is numeric now, some sources (Pixel) answer with a reCAPTCHA gate)
# ASCII-only script (PowerShell 5 reads .ps1 as ANSI).
$ErrorActionPreference = "SilentlyContinue"
$ua   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$main = "https://sezonlukdizi.cc"
$dom  = "sezonlukdizi.cc"
$results = @()

function Add-Result([string]$name, [bool]$ok, [string]$detail) {
    $script:results += [pscustomobject]@{ name = $name; ok = $ok; detail = $detail }
    $mark = if ($ok) { "OK  " } else { "FAIL" }
    Write-Output ("  [{0}] {1,-22} {2}" -f $mark, $name, $detail)
}

function Resolve-Ip([string]$h) {
    try { return (Resolve-DnsName $h -Server 8.8.8.8 -Type A -EA Stop | Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress } catch { return $null }
}

function Get-Page([string]$url, [string]$tag) {
    $file = Join-Path $env:TEMP ("slv_$tag.html")
    $h = ""; try { $h = ([uri]$url).Host } catch { $h = "" }
    $tip = @()
    if ($h -eq $dom -and $script:ip) { $tip = @("--resolve", "${dom}:443:$script:ip") }
    elseif ($h) { $ip2 = Resolve-Ip $h; if ($ip2) { $tip = @("--resolve", "${h}:443:$ip2") } }
    $code = & curl.exe -s -o $file -w "%{http_code}" -A $ua -e "$main/" -L @tip --max-time 30 --compressed $url
    if ("$code" -ne "200") { return $null }
    return [System.IO.File]::ReadAllText($file)
}

function Post-Form([string]$url, [string]$data, [string]$tag) {
    $file = Join-Path $env:TEMP ("slv_$tag.txt")
    $tip = @(); if ($script:ip) { $tip = @("--resolve", "${dom}:443:$script:ip") }
    $code = & curl.exe -s -o $file -w "%{http_code}" -A $ua -e "$main/" `
        -H "X-Requested-With: XMLHttpRequest" --max-time 25 --compressed @tip -d $data $url
    $body = ""; if (Test-Path $file) { $body = [System.IO.File]::ReadAllText($file) }
    return @{ code = "$code"; body = $body }
}

function Strip-Tags([string]$s) { return (($s -replace '<[^>]+>', ' ') -replace '\s+', ' ').Trim() }

# ---------------------------------------------------------------- build artifact
Write-Output "== SezonlukDizi =="
$cs3 = Join-Path $PSScriptRoot "..\SezonlukDizi\build\SezonlukDizi.cs3"
Add-Result "build:cs3" (Test-Path $cs3) $cs3

$script:ip = Resolve-Ip $dom
if (-not $script:ip) { Add-Result "dns" $false "cannot resolve $dom"; exit 1 }

# ---------------------------------------------------------------- 1..3 mainPage
$anchors = @()
$first1 = ""; $first2 = ""; $dataSrc = 0
$mp = Get-Page "$main/diziler.asp?siralama_tipi=id&s=1" "main1"
if ($mp) {
    $anchors = @([regex]::Matches($mp, '<a href="(/diziler/[^"]+)" class="column" title="([^"]*)">'))
    $dataSrc = ([regex]::Matches($mp, 'data-src="/i/dizi/')).Count
    if ($anchors.Count -gt 0) { $first1 = $anchors[0].Groups[2].Value.Trim() }
}
$noTitle = @($anchors | Where-Object { -not $_.Groups[2].Value.Trim() }).Count
Add-Result "mainPage:s=1" ($anchors.Count -ge 10 -and $noTitle -eq 0 -and $dataSrc -ge $anchors.Count -and $first1 -ne "") `
    ("{0} cards, {1} empty titles, {2} poster data-src, first='{3}'" -f $anchors.Count, $noTitle, $dataSrc, $first1)

$mp2 = Get-Page "$main/diziler.asp?siralama_tipi=id&s=2" "main2"
$a2 = @(); if ($mp2) { $a2 = @([regex]::Matches($mp2, '<a href="(/diziler/[^"]+)" class="column" title="([^"]*)">')) }
if ($a2.Count -gt 0) { $first2 = $a2[0].Groups[2].Value.Trim() }
Add-Result "mainPage:s=2" ($a2.Count -ge 10 -and $first2 -ne "" -and $first2 -ne $first1) `
    ("{0} cards, first='{1}'" -f $a2.Count, $first2)

$mp3 = Get-Page "$main/diziler.asp?kat=2&s=1" "kat2"
$a3 = @(); if ($mp3) { $a3 = @([regex]::Matches($mp3, '<a href="(/diziler/[^"]+)" class="column" title="([^"]*)">')) }
Add-Result "mainPage:kat=2" ($a3.Count -ge 5) ("{0} cards" -f $a3.Count)

# ---------------------------------------------------------------- 4. search
$r = Post-Form "$main/ajax/arama.asp" "q=breaking" "arama"
$sonuc = @(); $st = ""
try { $j = $r.body | ConvertFrom-Json; $st = $j.status
      if ($j.results) { foreach ($p in $j.results.PSObject.Properties) { if ($p.Value.results) { $sonuc += $p.Value.results } } }
} catch { $j = $null }
$dz = @($sonuc | Where-Object { $_.url -like '*/diziler/*' })
$img = @($dz | Where-Object { $_.image }).Count
Add-Result "search:arama" ($r.code -eq "200" -and $st -eq "success" -and $dz.Count -ge 1 -and $dz[0].title) `
    ("code={0} status={1}, {2} dizi sonucu, {3} poster, ilk='{4}'" -f $r.code, $st, $dz.Count, $img, $(if ($dz.Count) { $dz[0].title } else { "" }))

# ---------------------------------------------------------------- 5. load (series)
$seriesUrl = "$main" + $(if ($anchors.Count) { $anchors[0].Groups[1].Value } else { "/diziler/brothers.html" })
$slug = ([uri]$seriesUrl).AbsolutePath.Trim('/')   # "diziler/<slug>" or "<slug>"
$sDoc = Get-Page $seriesUrl "series"
$title = ""; $plot = ""; $poster = ""; $year = ""; $dur = ""; $genres = 0
if ($sDoc) {
    $tm = [regex]::Match($sDoc, '<div class="header[^"]*"[^>]*>([\s\S]{0,80}?)</div>')
    if ($tm.Success) { $title = Strip-Tags $tm.Groups[1].Value }
    $pm = [regex]::Match($sDoc, '(?s)id="dizibilgisi"[\s\S]{0,4000}?<img[^>]*?data-src="([^"]+)"')
    if ($pm.Success) { $poster = $pm.Groups[1].Value }
    $pl = [regex]::Match($sDoc, '(?s)id="tartismayorum-konu">([\s\S]{0,900}?)</span>')
    if ($pl.Success) { $plot = Strip-Tags $pl.Groups[1].Value }
    $di = $sDoc.IndexOf('id="dizibilgisi"'); $dd = $sDoc.IndexOf('id="dizidetay"')
    if ($di -ge 0 -and $dd -gt $di) {
        $seg = $sDoc.Substring($di, $dd - $di)
        $genres = ([regex]::Matches($seg, 'diziler\.asp\?tur=')).Count
    }
    $ym = [regex]::Match($sDoc, '(?s)class="extra content"[\s\S]{0,400}?<span[^>]*>\s*((?:19|20)\d{2})\s*</span>')
    if ($ym.Success) { $year = $ym.Groups[1].Value }
    $dm = [regex]::Match($sDoc, '<span[^>]*>\s*(\d+)\s*Dk\.')
    if ($dm.Success) { $dur = $dm.Groups[1].Value }
}
Add-Result "load:series" ($title -ne "" -and $poster -ne "" -and $plot.Length -gt 30 -and $genres -ge 1 -and $year -ne "" -and $dur -ne "") `
    ("title='{0}', poster={1}, plot={2}ch, {3} tur, {4}, {5} Dk." -f $title, $(if ($poster) { "yes" } else { "no" }), $plot.Length, $genres, $year, $dur)

# ---------------------------------------------------------------- 6. load (episodes)
$epSlug = ""
if ($slug -like "*/*") { $epSlug = $slug.Split('/')[1] } else { $epSlug = $slug }
$bDoc = Get-Page "$main/bolumler/$epSlug" "bolumler"
$rows = 0; $badRow = 0; $epUrl = ""; $epSample = ""
if ($bDoc) {
    foreach ($rm in [regex]::Matches($bDoc, '(?s)<tr[^>]*>[\s\S]*?</tr>')) {
        if ($rm.Value -notmatch '<td') { continue }
        $rows++
        $tds = @([regex]::Matches($rm.Value, '(?s)<td[^>]*>([\s\S]*?)</td>'))
        if ($tds.Count -lt 4) { $badRow++; continue }
        $sezonT = Strip-Tags $tds[1].Groups[1].Value
        $bolumT = Strip-Tags $tds[2].Groups[1].Value
        $adT    = $tds[3].Groups[1].Value
        if ($sezonT -notmatch '\d' -or $bolumT -notmatch '\d') { $badRow++ }
        if ($adT -notmatch '<a[^>]+href=') { $badRow++ }
        if (-not $epUrl) {
            $hm = [regex]::Match($adT, "href='([^']+)'")
            if (-not $hm.Success) { $hm = [regex]::Match($adT, 'href="([^"]+)"') }
            if ($hm.Success) {
                $epUrl = $hm.Groups[1].Value
                $epSample = (Strip-Tags $tds[2].Groups[1].Value) + " / " + (Strip-Tags $adT)
            }
        }
    }
}
$hasTable = ($bDoc -and $bDoc -match 'class="ui unstackable table"')
Add-Result "load:bolumler" ($hasTable -and $rows -ge 3 -and $badRow -eq 0 -and $epUrl -ne "") `
    ("table=$hasTable, {0} satir, {1} hatali, ilk='{2}', url={3}" -f $rows, $badRow, $epSample, $epUrl)

# ---------------------------------------------------------------- 7. load (actors)
$oDoc = Get-Page "$main/oyuncular/$epSlug" "oyuncular"
$cards = 0; $heads = 0; $pics = 0
if ($oDoc) {
    $oi = $oDoc.IndexOf('doubling')
    if ($oi -ge 0) {
        $seg = $oDoc.Substring($oi, [Math]::Min(9000, $oDoc.Length - $oi))
        $cards = ([regex]::Matches($seg, '<div class="ui card')).Count
        $heads = ([regex]::Matches($seg, '<div class="header">')).Count
        $pics  = ([regex]::Matches($seg, 'href="/oyuncu/')).Count
    }
}
Add-Result "load:oyuncular" ($cards -ge 3 -and $heads -ge 3 -and $pics -ge 6) `
    ("{0} kart, {1} isim, {2} oyuncu baglantisi" -f $cards, $heads, $pics)

# ---------------------------------------------------------------- 8. loadLinks
$epFull = if ($epUrl -like "http*") { $epUrl } else { "$main$epUrl" }
$eDoc = Get-Page $epFull "episode"
$bid = ""
if ($eDoc) {
    $bm = [regex]::Match($eDoc, '<div[^>]*id="dilsec"[^>]*\sdata-id="(\d+)"')
    if (-not $bm.Success) { $bm = [regex]::Match($eDoc, 'id="dilsec"[^>]*?data-id="(\d+)"') }
    if ($bm.Success) { $bid = $bm.Groups[1].Value }
}
Add-Result "loadLinks:dilsec" ($bid -ne "") ("bid={0} on {1}" -f $bid, $epFull)

$iframe = ""; $tried = 0; $altCount = 0; $st1 = ""; $st0 = ""; $idNum = $false
if ($bid) {
    $a1 = Post-Form "$main/ajax/dataAlternatif22.asp" "bid=$bid&dil=1" "alt1"
    $a0 = Post-Form "$main/ajax/dataAlternatif22.asp" "bid=$bid&dil=0" "alt0"
    $j1 = $null; $j0 = $null
    try { $j1 = $a1.body | ConvertFrom-Json; $st1 = $j1.status } catch { }
    try { $j0 = $a0.body | ConvertFrom-Json; $st0 = $j0.status } catch { }
    $ids = @()
    if ($j1 -and $j1.data) { $altCount = $j1.data.Count; $ids += @($j1.data | ForEach-Object { $_.id }) }
    if ($j0 -and $j0.data) { $ids += @($j0.data | ForEach-Object { $_.id }) }
    if ($ids.Count -gt 0) { $idNum = ($ids[0] -is [int] -or $ids[0] -is [long] -or $ids[0] -is [double]) }
    foreach ($id in $ids) {
        if ($tried -ge 6) { break }
        $tried++
        $e = Post-Form "$main/ajax/dataEmbed22.asp" "id=$id" "emb"
        $im = [regex]::Match($e.body, '<iframe[^>]*\ssrc="([^"]+)"')
        if ($im.Success -and $im.Groups[1].Value -notmatch 'reCAPTCHA') { $iframe = $im.Groups[1].Value; break }
    }
}
Add-Result "loadLinks:embed" ($st1 -eq "success" -and $st0 -eq "success" -and $idNum -and $iframe -ne "") `
    ("dil1={0} dil0={1}, {2} alternatif, id numeric={3}, {4} deneme, iframe={5}" -f $st1, $st0, $altCount, $idNum, $tried, $iframe)

# ---------------------------------------------------------------- summary
$bad = @($results | Where-Object { -not $_.ok })
Write-Output ""
Write-Output ("{0}/{1} checks passed" -f ($results.Count - $bad.Count), $results.Count)
if ($bad.Count -gt 0) { $bad | ForEach-Object { Write-Output ("  FAIL {0}: {1}" -f $_.name, $_.detail) }; exit 1 }
Write-Output "SezonlukDizi chain: ALL OK"
exit 0
