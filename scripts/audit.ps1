# Per-provider live audit: homepage + search endpoint reachability.
# ASCII-only script (PowerShell 5 reads .ps1 as ANSI).
$ErrorActionPreference = "SilentlyContinue"
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$outDir = Join-Path $PSScriptRoot "audit-html"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

function Resolve-Ip([string]$h) {
    try { return (Resolve-DnsName $h -Server 8.8.8.8 -Type A -ErrorAction Stop | Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress } catch { return $null }
}

function Fetch([string]$label, [string]$url) {
    if (-not $url) { return [pscustomobject]@{ Label=$label; Url=""; Code="SKIP"; Final=""; Size=0; Title=""; Note="no url" } }
    $h = $null; $ip = $null
    try { $h = ([uri]$url).Host } catch { }
    if ($h) { $ip = Resolve-Ip $h }
    if (-not $ip) { return [pscustomobject]@{ Label=$label; Url=$url; Code="NX"; Final=""; Size=0; Title=""; Note="dns" } }
    $file = Join-Path $outDir ($label -replace '[^A-Za-z0-9_.-]','_')
    $resp = curl.exe -s -o $file -w "%{http_code}|%{url_effective}|%{size_download}" --resolve "${h}:443:$ip" -A $ua -H "X-Requested-With: fetch" -e "$url" -L --max-time 30 --compressed $url 2>$null
    $p = "$resp".Split("|")
    $code = $p[0]; $final = $p[1]; $size = 0; [void][int]::TryParse($p[2], [ref]$size)
    $title = ""; $body = ""
    if (Test-Path $file) {
        $bytes = [IO.File]::ReadAllBytes($file)
        $body = [Text.Encoding]::UTF8.GetString($bytes, 0, [Math]::Min($bytes.Length, 400000))
        if ($body -match '<title[^>]*>([\s\S]{0,90}?)</title>') { $title = ($Matches[1] -replace '\s+',' ').Trim() }
    }
    $note = ""
    if ($title -match 'Just a moment') { $note = "CF-CHALLENGE" }
    elseif ($title -match 'Domain is for sale|sat.n|parking|Buy this domain|domain is parked') { $note = "PARKED" }
    elseif ($code -eq "404") { $note = "404" }
    elseif ($size -lt 700 -and $code -eq "200") { $note = "EMPTY" }
    return [pscustomobject]@{ Label=$label; Url=$url; Code=$code; Final=$final; Size=$size; Title=$title; Note=$note }
}

# provider | search url ("" = homepage only)
$targets = @(
    @("AnimeciX",          "https://animecix.tv/secure/search/matrix?limit=20"),
    @("BelgeselX",         ""),
    @("CizgiMax",          "https://cizgimax.online/ara/?q=keloglan"),
    @("DiziBox",           "https://www.dizibox.live/?s=breaking"),
    @("DiziKorea",         ""),
    @("Dizilla",           "https://dizilla.club/?s=breaking"),
    @("DiziMom",           "https://www.dizimom.com/?s=breaking"),
    @("DiziPal",           "https://dizipal1219.com/"),
    @("DiziYou",           "https://www.diziyou.com/?s=breaking"),
    @("FilmMakinesi",      "https://filmmakinesi.to/arama/?s=matrix"),
    @("FilmModu",          "https://www.filmmodu.nl/film-ara?term=matrix"),
    @("FullHDFilmizlesene","https://www.fullhdfilmizlesene.now/arama/matrix"),
    @("FullPorner",        "https://fullporner.com/search?q=matrix&p=1"),
    @("HDFilmCehennemi",   "https://www.hdfilmcehennemi.nl/search?q=matrix"),
    @("HQPorner",          "https://hqporner.com/?q=matrix&p=1"),
    @("InatBox",           ""),
    @("IzleAI",            ""),
    @("JetFilmizle",       ""),
    @("KoreanTurk",        "https://www.koreanturk.com/"),
    @("KultFilmler",       "https://kultfilmler.net/?s=matrix"),
    @("PornHub",           "https://www.pornhub.com/video/search?search=matrix"),
    @("RareFilmm",         "https://rarefilmm.com/?s=matrix"),
    @("SezonlukDizi",      "https://sezonlukdizi.cc/diziler.asp?adi=breaking"),
    @("SpankBang",         "https://spankbang.com/s/matrix/1/?o=new&d=10"),
    @("UncutMaza",         "https://uncutmaza.cc/page/1?s=matrix"),
    @("WebteIzle",         "https://webteizle.info/filtre?a=matrix"),
    @("xHamster",          "https://xhamster.com/search/matrix/?page=1&x_platform_switch=desktop"),
    @("YouTube",           "https://invidious.f5.si/api/v1/search?q=matrix&region=TR&page=1&type=video&fields=videoId,title")
)

$rows = @()
foreach ($t in $targets) {
    $p = $t[0]; $search = $t[1]
    $meta = Import-Csv (Join-Path $PSScriptRoot "audit-meta.csv") | Where-Object { $_.Provider -eq $p } | Select-Object -First 1
    $homeUrl = $meta.MainUrl
    # normalize: some mainUrls are API files, use the raw host root
    $h = Fetch "$p-home" $homeUrl
    $s = $null
    if ($search) { $s = Fetch "$p-search" $search }
    $rows += [pscustomobject]@{
        Provider = $p
        HomeCode = $h.Code
        HomeSize = $h.Size
        HomeTitle= $h.Title
        HomeNote = $h.Note
        SearchCode = if ($s) { $s.Code } else { "-" }
        SearchSize = if ($s) { $s.Size } else { 0 }
        SearchTitle= if ($s) { $s.Title } else { "" }
        SearchNote = if ($s) { $s.Note } else { "-" }
    }
    "{0,-18} home={1,-4} {2,-7} | search={3,-4} {4,-7} | {5}" -f $p, $h.Code, ("$($h.Size)" + $(if($h.Note){" " + $h.Note}else{""})), $(if($s){$s.Code}else{"-"}), $(if($s){"$($s.Size)"+$(if($s.Note){" "+$s.Note}else{""})}else{""}), $h.Title
}

$rows | Sort-Object Provider | Format-Table Provider,HomeCode,HomeSize,HomeNote,SearchCode,SearchSize,SearchNote,HomeTitle -AutoSize | Out-String -Width 250 | Write-Output
$rows | Export-Csv (Join-Path $PSScriptRoot "audit-results.csv") -NoTypeInformation -Encoding UTF8
