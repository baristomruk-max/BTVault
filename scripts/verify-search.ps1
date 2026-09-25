# verify-search.ps1
# For every provider with a GET search endpoint, fetch the live search page and
# check that the CSS class/id tokens used inside search() are present in the HTML.
# ASCII-only script (PowerShell 5 reads .ps1 as ANSI).
$ErrorActionPreference = "SilentlyContinue"
$root = Split-Path $PSScriptRoot -Parent
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$outDir = Join-Path $PSScriptRoot "site-checks-search"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

# provider -> live search url ("" means POST / dynamic search, skipped)
$targets = [ordered]@{
    "AnimeciX"          = "https://animecix.tv/secure/search/matrix?limit=20"
    "CizgiMax"          = "https://cizgimax.online/ara/?q=keloglan"
    "Dizilla"           = "https://dizilla.club/?s=breaking"
    "DiziMom"           = "https://www.dizimom.com/?s=breaking"
    "DiziYou"           = "https://www.diziyou.com/?s=breaking"
    "FilmMakinesi"      = "https://filmmakinesi.to/arama/?s=matrix"
    "FilmModu"          = "https://www.filmmodu.nl/film-ara?term=matrix"
    "FullHDFilmizlesene"= "https://www.fullhdfilmizlesene.now/arama/matrix"
    "FullPorner"        = "https://fullporner.com/search?q=matrix&p=1"
    "HDFilmCehennemi"   = "https://www.hdfilmcehennemi.nl/search?q=matrix"
    "HQPorner"          = "https://hqporner.com/?q=matrix&p=1"
    "JetFilmizle"       = "https://jetfilmizle.now/arama?q=matrix"
    "KultFilmler"       = "https://kultfilmler.net/?s=matrix"
    "PornHub"           = "https://www.pornhub.com/video/search?search=matrix"
    "RareFilmm"         = "https://rarefilmm.com/?s=matrix"
    "SpankBang"         = "https://spankbang.com/s/matrix/1/?o=new&d=10"
    "UncutMaza"         = "https://uncutmaza.cc/page/1?s=matrix"
    "WebteIzle"         = "https://webteizle.info/filtre?a=matrix"
    "xHamster"          = "https://xhamster.com/search/matrix/?page=1&x_platform_switch=desktop"
    "YouTube"           = "https://invidious.f5.si/api/v1/search?q=matrix&region=TR&page=1&type=video&fields=videoId,title"
}

# for JSON endpoints there are no CSS selectors - check these keys instead
$jsonKeys = @{
    "AnimeciX" = '"results"'
    "YouTube"  = '"videoId"'
}

function Resolve-Ip([string]$h) {
    try { return (Resolve-DnsName $h -Server 8.8.8.8 -Type A -EA Stop | Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress } catch { return $null }
}

function Get-SearchTokens([string]$code) {
    $i = $code.IndexOf("suspend fun search")
    if ($i -lt 0) { return @() }
    $rest = $code.Substring($i)
    # stop at the next top level function
    $j = $rest.IndexOf("override suspend fun", 20)
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
    $url = $targets[$name]
    $ktDir = Join-Path (Join-Path $root $name) "src\main\kotlin"
    $kt = Get-ChildItem $ktDir -Recurse -Filter *.kt -EA SilentlyContinue |
          Where-Object { $_.BaseName -eq $name } | Select-Object -First 1
    $tokens = @()
    if ($kt) { $tokens = Get-SearchTokens ([System.IO.File]::ReadAllText($kt.FullName)) }

    if (-not $url) {
        $rows += [pscustomobject]@{ Provider = $name; Code = "-"; Size = 0; Tokens = $tokens.Count; Found = 0; Missing = "(no GET search)" }
        continue
    }

    $h = $null; try { $h = ([uri]$url).Host } catch { }
    $ip = $null; if ($h) { $ip = Resolve-Ip $h }
    if (-not $ip) {
        $rows += [pscustomobject]@{ Provider = $name; Code = "NX"; Size = 0; Tokens = $tokens.Count; Found = 0; Missing = "dns" }
        continue
    }

    $file = Join-Path $outDir ($name + ".html")
    $r = curl.exe -s -o $file -w "%{http_code}" --resolve "${h}:443:$ip" -A $ua -H "X-Requested-With: fetch" -e $url -L --max-time 30 --compressed $url 2>$null
    $html = ""
    if (Test-Path $file) { $html = [System.IO.File]::ReadAllText($file) }

    $found = 0; $missing = @()
    if ($tokens.Count -eq 0 -and $jsonKeys[$name]) {
        $key = $jsonKeys[$name]
        if ($html -match [regex]::Escape($key)) { $found = 1; $tokens = @($key) } else { $missing = @($key) }
    }
    foreach ($t in $tokens) {
        if ($t -and $html -match ('(?<![\w-])' + [regex]::Escape($t) + '(?![\w-])')) { $found++ }
        else { $missing += $t }
    }

    $rows += [pscustomobject]@{
        Provider = $name; Code = "$r"; Size = $html.Length
        Tokens = $tokens.Count; Found = $found; Missing = ($missing -join ",")
    }
    "{0,-20} {1,-4} {2,-8} tokens={3}/{4}  {5}" -f $name, $r, $html.Length, $found, $tokens.Count, ($missing -join ",")
}

""
$rows | Sort-Object Provider | Format-Table Provider,Code,Size,Tokens,Found,Missing -AutoSize | Out-String -Width 250 | Write-Output
$rows | Export-Csv -Path (Join-Path $PSScriptRoot "search-selectors.csv") -NoTypeInformation -Encoding UTF8
