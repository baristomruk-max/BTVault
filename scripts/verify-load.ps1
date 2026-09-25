# verify-load.ps1
# Phase 2: for each provider with a GET search endpoint, take result links,
# open the detail pages and check the CSS tokens used inside load().
# ASCII-only script (PowerShell 5 reads .ps1 as ANSI).
$ErrorActionPreference = "SilentlyContinue"
$root = Split-Path $PSScriptRoot -Parent
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$outDir = Join-Path $PSScriptRoot "site-checks-load"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$targets = [ordered]@{
    "CizgiMax"          = "https://cizgimax.online/ara/?q=keloglan"
    "Dizilla"           = "https://dizilla.club/?s=breaking"
    "DiziMom"           = "https://www.dizimom.com/?s=breaking"
    "DiziYou"           = "https://www.diziyou.com/?s=breaking"
    "FilmMakinesi"      = "https://filmmakinesi.to/arama/?s=matrix"
    "FilmModu"          = "https://www.filmmodu.nl/film-ara?term=matrix"
    "FullHDFilmizlesene"= "https://www.fullhdfilmizlesene.now/arama/matrix"
    "HDFilmCehennemi"   = "https://www.hdfilmcehennemi.nl/search?q=matrix"
    "KultFilmler"       = "https://kultfilmler.net/?s=matrix"
    "FullPorner"        = "https://fullporner.com/search?q=matrix&p=1"
    "HQPorner"          = "https://hqporner.com/?q=matrix&p=1"
    "JetFilmizle"       = "https://jetfilmizle.now/arama?q=matrix"
    "PornHub"           = "https://www.pornhub.com/video/search?search=matrix"
    "RareFilmm"         = "https://rarefilmm.com/?s=heat"
    "SezonlukDizi"      = "https://sezonlukdizi.cc/diziler.asp?adi=breaking"
    "xHamster"          = "https://xhamster.com/search/matrix/?page=1&x_platform_switch=desktop"
}

# query word expected inside a result link (used to rank candidates)
$queries = @{
    "CizgiMax"           = "keloglan"
    "Dizilla"            = "breaking"
    "DiziMom"            = "breaking"
    "DiziYou"            = "breaking"
    "FilmMakinesi"       = "matrix"
    "FilmModu"           = "matrix"
    "FullHDFilmizlesene" = "matrix"
    "HDFilmCehennemi"    = "matrix"
    "KultFilmler"        = "matrix"
    "FullPorner"         = "matrix"
    "HQPorner"           = "matrix"
    "JetFilmizle"        = "matrix"
    "PornHub"            = "matrix"
    "RareFilmm"          = "heat"
    "SezonlukDizi"       = "breaking"
    "xHamster"           = "matrix"
}

# links that are certainly a detail page (checked before the query-word ranking)
$prefer = @{
    "PornHub" = 'view_video\.php\?viewkey='
}

# second detail page (series / season page) - unioned with the movie detail page,
# because series-only selectors (seasons, episodes) are absent on movie pages
$extras = @{
    "KultFilmler"      = "https://kultfilmler.net/dizi/sherlock-izle/"
    "HDFilmCehennemi"  = "https://www.hdfilmcehennemi.nl/dizi/angel-di-maria-breaking-down-the-wall-3/"
    "Dizilla"          = "https://dizilla.club/the-simpsons-1-sezon-c01"
    "CizgiMax"         = "https://cizgimax.online/diziler/keloglan-izle/"
}

# selectors that are pure alternatives in the code (another selector always wins),
# so their absence is not a breakage
$allow = @{
    "KultFilmler" = @("ccast", "cm")
    "Dizilla"     = @("mv-det-p")
    # span.percent is injected by the front-end after load (JSON fallback in code)
    "PornHub"     = @("percent")
}

# never detail pages
$badPath = '(\.css|\.js|\.png|\.jpg|\.jpeg|\.webp|\.svg|\.gif|\.ico|\.woff2?|\.mp4|\.m3u8|\.pdf)$' +
           '|/(assets|static|dist|cdn-cgi|wp-content|wp-includes|js|css|img|images|fonts|api|feed|giris|uyelik|iletisim|gizlilik|hakkinda|sartlar|kayit)/' +
           '|(^|/)(page|tag|kategori|category|tur|yil|arsiv|tum-diziler|kesfet|en-cok-izlenen|film-arsivi|son-bolumler|yeni-eklenenler|giris|ulkeler|chat-room|dizi-takvimi|haberler|takvim|sosyal-akis|uygulamalar|istek|rastgele|bolumler|oyuncular|iletisim|hakkinda|information|cookie-notice|dmca|privacy|terms)(/|$)' +
           '|(^|/)(categories|channels|pornstars|albums|trending|best|live|amateur|community|forums|stories|tags|playlists)(/|$)|(/ara/|/search|/arama|/filtre|diziler\.asp|\?s=|\?q=|\?term=|\?adi=|\?page=)'

function Resolve-Ip([string]$h) {
    try { return (Resolve-DnsName $h -Server 8.8.8.8 -Type A -EA Stop | Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress } catch { return $null }
}

function Get-LoadTokens([string]$code) {
    $i = $code.IndexOf("suspend fun load(")
    if ($i -lt 0) { $i = $code.IndexOf("fun load(") }
    if ($i -lt 0) { return @() }
    $rest = $code.Substring($i)
    $j = $rest.IndexOf("override suspend fun", 30)
    if ($j -gt 0) { $rest = $rest.Substring(0, $j) }

    $tokens = New-Object System.Collections.Generic.List[string]
    foreach ($m in [regex]::Matches($rest, '(?:select|selectFirst|getElementsByClass|getElementsByTag)\(\s*"([^"]+)"')) {
        $sel = $m.Groups[1].Value
        foreach ($c in [regex]::Matches($sel, '\.([A-Za-z0-9_-]+)')) { $tokens.Add($c.Groups[1].Value) }
        foreach ($h in [regex]::Matches($sel, '#([A-Za-z0-9_-]+)'))   { $tokens.Add($h.Groups[1].Value) }
        foreach ($t in [regex]::Matches($sel, '(?:^|[\s>+~])([a-z][a-z0-9-]{2,})')) { $tokens.Add($t.Groups[1].Value) }
    }
    return @($tokens | Sort-Object -Unique)
}

$rows = @()
foreach ($name in $targets.Keys) {
    $searchUrl = $targets[$name]
    $kt = Get-ChildItem (Join-Path (Join-Path $root $name) "src\main\kotlin") -Recurse -Filter *.kt -EA SilentlyContinue |
          Where-Object { $_.BaseName -eq $name } | Select-Object -First 1
    if (-not $kt) { continue }
    $code   = [System.IO.File]::ReadAllText($kt.FullName)
    $tokens = Get-LoadTokens $code

    $baseUri = $null
    try { $baseUri = [uri]$searchUrl } catch { }
    $host_ = if ($baseUri) { $baseUri.Host } else { $null }
    $ip = $null; if ($host_) { $ip = Resolve-Ip $host_ }
    if (-not $ip) {
        $rows += [pscustomobject]@{ Provider=$name; Detail="-"; Tokens=$tokens.Count; Found=0; Missing="dns"; Url="" }
        continue
    }

    $searchFile = Join-Path $outDir ($name + ".search.html")
    curl.exe -s -o $searchFile --resolve "${host_}:443:$ip" -A $ua -H "X-Requested-With: fetch" -e $searchUrl -L --max-time 30 --compressed $searchUrl 2>$null | Out-Null
    $html = ""; if (Test-Path $searchFile) { $html = [System.IO.File]::ReadAllText($searchFile) }
    $html = $html -replace '\\/', '/' -replace '\\"', '"'

    $qWord = $queries[$name]
    if (-not $qWord) { $qWord = [guid]::NewGuid().ToString() }
    $prWord = $prefer[$name]
    $pre  = New-Object System.Collections.Generic.List[string]
    $hot  = New-Object System.Collections.Generic.List[string]
    $cold = New-Object System.Collections.Generic.List[string]
    foreach ($m in [regex]::Matches($html, 'href\s*=\s*["'']([^"''#]{3,300})["'']')) {
        $href = ($m.Groups[1].Value -replace '&amp;', '&').Trim()
        if ($href -match '^javascript:|^mailto:|^tel:|^data:') { continue }
        $abs = $null
        try { $abs = [uri]::new($baseUri, $href).AbsoluteUri } catch { continue }
        if ($abs -notmatch ('^https?://(' + [regex]::Escape($host_) + '|www\.)')) { continue }
        if ($abs -eq $searchUrl) { continue }
        $path = ""; try { $path = ([uri]$abs).AbsolutePath } catch { }
        if ($path.Length -lt 3) { continue }                 # site root
        if ($abs -match $badPath) { continue }
        if ($prWord -and $abs -match $prWord)     { $pre.Add($abs) }
        elseif ($abs -match $qWord)                { $hot.Add($abs) }
        else                                       { $cold.Add($abs) }
        if (($pre.Count + $hot.Count + $cold.Count) -ge 200) { break }
    }
    $links = New-Object System.Collections.Generic.List[string]
    foreach ($x in $pre)  { $links.Add($x) }
    foreach ($x in $hot)  { $links.Add($x) }
    foreach ($x in $cold) { $links.Add($x) }

    $checked = 0; $bestFound = -1; $bestUrl = ""; $bestMissing = "(no detail link)"; $bestHtml = $null
    foreach ($dUrl in $links) {
        if ($checked -ge 12) { break }
        $dHost = $null; try { $dHost = ([uri]$dUrl).Host } catch { continue }
        $dIp = Resolve-Ip $dHost
        if (-not $dIp) { continue }

        $dFile = Join-Path $outDir ($name + ".detail$checked.html")
        $c = curl.exe -s -o $dFile -w "%{http_code}" --resolve "${dHost}:443:$dIp" -A $ua -H "X-Requested-With: fetch" -e $searchUrl -L --max-time 30 --compressed $dUrl 2>$null
        if ("$c" -ne "200") { continue }
        $dHtml = ""; if (Test-Path $dFile) { $dHtml = [System.IO.File]::ReadAllText($dFile) }
        if ($dHtml.Length -lt 500) { continue }
        $checked++

        $found = 0; $missing = @()
        foreach ($t in $tokens) {
            if ($t -and $dHtml -match ('(?<![\w-])' + [regex]::Escape($t) + '(?![\w-])')) { $found++ }
            else { $missing += $t }
        }
        if ($found -gt $bestFound) { $bestFound = $found; $bestUrl = $dUrl; $bestMissing = ($missing -join ","); $bestHtml = $dHtml }
    }
    if (-not $bestHtml) { $bestHtml = "" }
    if ($bestFound -lt 0) { $bestFound = 0 }

    # union with a series/season page (series-only selectors live there)
    $extraUrl = $extras[$name]
    if ($extraUrl -and $bestFound -gt 0) {
        $eHost = $null; try { $eHost = ([uri]$extraUrl).Host } catch { $eHost = $null }
        $eIp = if ($eHost) { Resolve-Ip $eHost } else { $null }
        if ($eIp) {
            $eFile = Join-Path $outDir ($name + ".extra.html")
            $ec = curl.exe -s -o $eFile -w "%{http_code}" --resolve "${eHost}:443:$eIp" -A $ua -H "X-Requested-With: fetch" -e $extraUrl -L --max-time 30 --compressed $extraUrl 2>$null
            if ("$ec" -eq "200" -and (Test-Path $eFile)) {
                $eHtml = [System.IO.File]::ReadAllText($eFile)
                $found = 0; $missing = @()
                foreach ($t in $tokens) {
                    if (-not $t) { continue }
                    $hit = ($bestHtml -match ('(?<![\w-])' + [regex]::Escape($t) + '(?![\w-])')) -or
                           ($eHtml  -match ('(?<![\w-])' + [regex]::Escape($t) + '(?![\w-])'))
                    if ($hit) { $found++ } else { $missing += $t }
                }
                if ($found -gt $bestFound) {
                    $bestFound = $found
                    $bestUrl   = "$extraUrl  (+ movie page)"
                    $bestMissing = ($missing -join ",")
                }
            }
        }
    }

    $allowList = $allow[$name]
    if ($allowList) {
        foreach ($a in $allowList) {
            if ($bestMissing -split ',' -contains $a) {
                $bestMissing = (($bestMissing -split ',' | Where-Object { $_ -ne $a }) -join ",")
                $bestFound++
            }
        }
        if (-not $bestMissing) { $bestMissing = "" }
    }

    $rows += [pscustomobject]@{
        Provider = $name
        Detail   = if ($checked -gt 0) { "200" } else { "none" }
        Tokens   = $tokens.Count
        Found    = $bestFound
        Missing  = $bestMissing
        Url      = $bestUrl
    }
    "{0,-20} {1,-6} tokens={2}/{3}  {4}" -f $name, $rows[-1].Detail, $bestFound, $tokens.Count, $bestMissing
}

""
$rows | Format-Table Provider,Detail,Tokens,Found,Missing,Url -AutoSize | Out-String -Width 220 | Write-Output
$rows | Export-Csv -Path (Join-Path $PSScriptRoot "load-selectors.csv") -NoTypeInformation -Encoding UTF8
