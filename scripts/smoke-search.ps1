$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

function Get-Ip($h) {
    try { return (Resolve-DnsName $h -Server 8.8.8.8 -Type A -ErrorAction Stop | Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress } catch { return $null }
}

# name, search url
$targets = @(
    @("FilmModu",           "https://www.filmmodu.nl/film-ara?term=matrix"),
    @("DiziYou",            "https://www.diziyou.com/?s=breaking"),
    @("DiziMom",            "https://www.dizimom.com/?s=breaking"),
    @("FullHDFilmizlesene", "https://www.fullhdfilmizlesene.now/arama/matrix"),
    @("xHamster",           "https://xhamster.com/search/matrix/?page=1&x_platform_switch=desktop"),
    @("PornHub",            "https://www.pornhub.com/video/search?search=matrix"),
    @("HQPorner",           "https://hqporner.com/?q=matrix&p=1"),
    @("FullPorner",         "https://fullporner.com/search?q=matrix&p=1"),
    @("SpankBang",          "https://spankbang.com/s/matrix/1/?o=new&d=10"),
    @("UncutMaza",          "https://uncutmaza.cc/page/1?s=matrix"),
    @("Dizilla",            "https://dizilla.club/?s=breaking"),
    @("IzleAI",             "https://izle.ai/arama/?q=matrix"),
    @("JetFilmizle",        "https://www.jetfilmizle.vip/"),
    @("RareFilmm",          "https://www.rarefilmm.com/?s=matrix"),
    @("CizgiMax",           "https://www.cizgimax.com/?s=matrix"),
    @("DiziKorea",          "https://dizikorea.info/?s=breaking"),
    @("KoreanTurk",         "https://www.koreanturk.com/")
)

foreach ($t in $targets) {
    $name = $t[0]; $url = $t[1]
    $h = ([uri]$url).Host
    $ip = Get-Ip $h
    if (-not $ip) { "{0,-20} NXDOMAIN  {1}" -f $name, $h; continue }
    $out = Join-Path $env:TEMP ("smoke_" + $name + ".html")
    $resp = curl.exe -s -o $out -w "%{http_code}|%{url_effective}|%{size_download}" --resolve "${h}:443:$ip" -A $ua -e "$url" -L --max-time 25 --compressed $url 2>$null
    $title = ""
    if (Test-Path $out) {
        $c = [IO.File]::ReadAllText($out)
        if ($c -match '<title[^>]*>([\s\S]{0,60}?)</title>') { $title = ($Matches[1] -replace '\s+', ' ').Trim() }
    }
    "{0,-20} {1}  TITLE={2}" -f $name, $resp, $title
}
