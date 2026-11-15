$ErrorActionPreference = "SilentlyContinue"
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$cands = @("https://www.dizimom.surf","https://dizimom.surf","https://www.dizimom.plus","https://dizimom.plus","https://www.dizimom.site","https://www.dizimom.online","https://www.dizimom.xyz","https://www.dizimom.top","https://www.dizimom.store","https://www.dizimom.live","https://www.dizimom.net")
foreach ($url in $cands) {
    $u = [uri]$url; $h = $u.Host
    $ip = (Resolve-DnsName $h -Server 8.8.8.8 -Type A -ErrorAction SilentlyContinue | Where-Object IPAddress | Select-Object -First 1).IPAddress
    if (-not $ip) { "$url NXDOMAIN"; continue }
    $f = Join-Path $env:TEMP ("dmprobe_" + $h + ".html")
    $r = curl.exe -s -o $f -w "%{http_code}|%{url_effective}|%{size_download}" --resolve "${h}:443:$ip" -A $ua -L --max-time 20 --compressed $url 2>$null
    $t = ""; $marker = ""
    if (Test-Path $f) {
        $html = [System.IO.File]::ReadAllText($f)
        if ($html -match '<title[^>]*>([\s\S]{0,90}?)</title>') { $t = ($Matches[1] -replace '\s+',' ').Trim() }
        if ($html -match 'episode-box') { $marker = "HAS-episode-box" }
    }
    "$url -> $r title=$t $marker"
}
