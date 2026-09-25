# verify-izleai.ps1
# 720PizleAI (selcukflix.com) - full chain check after the Next.js + encrypted JSON rewrite:
#   1. mainPage : POST /api/bg/findMovies (page 1 vs page 2 must differ)
#   2. search   : POST /api/bg/searchContent (AES-256-CBC payload)
#   3. load     : __NEXT_DATA__ -> props.pageProps.secureData -> contentItem
#   4. loadLinks: RelatedResults.getMoviePartSourcesById_* -> iframe -> source2.php -> master.m3u8
# ASCII-only script (PowerShell 5 reads .ps1 as ANSI).
$ErrorActionPreference = "SilentlyContinue"
$ua  = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$main = "https://selcukflix.com"
$host_ = "selcukflix.com"
$seed = "!!22xx!!90!!"
$results = @()

function Resolve-Ip([string]$h) {
    try { return (Resolve-DnsName $h -Server 8.8.8.8 -Type A -EA Stop | Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress } catch { return $null }
}

# same primitive as the Kotlin code: sha256(seed) -> base64 -> first 32 chars -> AES-256-CBC, IV = 0
function Unprotect([string]$b64) {
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        $hash = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($seed))
        $key = [Text.Encoding]::UTF8.GetBytes([Convert]::ToBase64String($hash).Substring(0, 32))
        $aes = [Security.Cryptography.Aes]::Create()
        $aes.Key = $key
        $aes.IV = New-Object byte[] 16
        $aes.Mode = "CBC"
        $aes.Padding = "PKCS7"
        $data = [Convert]::FromBase64String($b64)
        $plain = $aes.CreateDecryptor().TransformFinalBlock($data, 0, $data.Length)
        return [Text.Encoding]::UTF8.GetString($plain)
    } catch { return $null }
}

function Post-Api([string]$path, [string]$query, [string]$tag) {
    $file = Join-Path $env:TEMP ("izleai_" + $tag + ".json")
    $url  = "$main$path"
    if ($query) { $url = "$url" + "?" + $query }
    $code = curl.exe -s -o $file -w "%{http_code}" -A $ua -X POST -e "$main/" `
        -H "X-Requested-With: XMLHttpRequest" -H "Accept: application/json" `
        --resolve "${host_}:443:$script:ip" --max-time 30 --compressed $url
    if ("$code" -ne "200") { return $null }
    $json = [System.IO.File]::ReadAllText($file)
    try { $env_ = $json | ConvertFrom-Json } catch { return $null }
    if (-not $env_.response) { return $null }
    $plain = Unprotect $env_.response
    if (-not $plain) { return $null }
    try { return $plain | ConvertFrom-Json } catch { return $null }
}

$ip = Resolve-Ip $host_
if (-not $ip) { Write-Output "FAIL dns"; exit 1 }

# ---------------------------------------------------------------- 1. mainPage
$base = "releaseYearStart=-1&releaseYearEnd=-1&imdbPointMin=-1&imdbPointMax=-1&categoryIdsComma=&countryIdsComma=&orderType=date_desc&languageId=-1&currentPageCount=24&queryStr=&categorySlugsComma=&countryCodesComma="
$p1 = Post-Api "/api/bg/findMovies" "$base&currentPage=1" "fm1"
$p2 = Post-Api "/api/bg/findMovies" "$base&currentPage=2" "fm2"
$fm1 = @($p1.result); $fm2 = @($p2.result)
$mainOk = ($fm1.Count -eq 24 -and $fm2.Count -eq 24 -and $fm1[0].used_slug -ne $fm2[0].used_slug)
$results += [pscustomobject]@{ Step="mainPage"; Ok=$mainOk; Info=("page1={0} page2={1} first={2}" -f $fm1.Count, $fm2.Count, $fm1[0].used_slug) }
if (-not $mainOk) { $results | Format-Table -AutoSize | Out-String | Write-Output; exit 1 }

# ---------------------------------------------------------------- 2. search
$sr = Post-Api "/api/bg/searchContent" "searchterm=matrix" "search"
$items = @($sr.result)
$movies = @($items | Where-Object { $_.used_type -eq "Movies" })
$srOk = ($sr.state -eq $true -and $movies.Count -gt 0 -and $movies[0].used_slug -like "film/*")
$results += [pscustomobject]@{ Step="search"; Ok=$srOk; Info=("total={0} movies={1} slug={2}" -f $items.Count, $movies.Count, $movies[0].used_slug) }

# ---------------------------------------------------------------- 3. load
$slug = $movies[0].used_slug
if (-not $slug) { $slug = $fm1[0].used_slug }
$detailUrl = "$main/$slug"
$detailFile = Join-Path $env:TEMP "izleai_detail.html"
$code = curl.exe -s -o $detailFile -w "%{http_code}" -A $ua -e $detailUrl --resolve "${host_}:443:$ip" --max-time 30 --compressed $detailUrl
$html = [System.IO.File]::ReadAllText($detailFile)
$tag = '<script id="__NEXT_DATA__" type="application/json">'
$ci = $null; $related = $null
$s = $html.IndexOf($tag)
if ($s -ge 0) {
    $s += $tag.Length
    $e = $html.IndexOf("</script>", $s)
    try {
        $next = $html.Substring($s, $e - $s) | ConvertFrom-Json
        $plain = Unprotect $next.props.pageProps.secureData
        if ($plain) {
            $doc = $plain | ConvertFrom-Json
            $ci = $doc.contentItem
            $related = $doc.RelatedResults
        }
    } catch { }
}
$loadOk = ("$code" -eq "200" -and $ci -and $ci.original_title -and $ci.poster_url -and $ci.release_year -and $ci.categories)
$results += [pscustomobject]@{ Step="load"; Ok=[bool]$loadOk; Info=("{0} | {1} | {2} | {3}" -f $ci.original_title, $ci.release_year, $ci.categories, $detailUrl) }

# ---------------------------------------------------------------- 4. loadLinks
$iframe = $null
if ($related) {
    foreach ($prop in $related.PSObject.Properties) {
        if ($prop.Name -ne "getMovieSourcesById" -and $prop.Name -notlike "getMoviePartSourcesById_*") { continue }
        foreach ($item in @($prop.Value.result)) {
            if (-not $item.source_content) { continue }
            $m = [regex]::Match($item.source_content, 'src\s*=\s*["'']([^"'']+)["'']')
            if ($m.Success) { $iframe = $m.Groups[1].Value }
        }
    }
}
$streamOk = $false; $info = "(no source)"
if ($iframe) {
    if ($iframe.StartsWith("//")) { $iframe = "https:$iframe" }
    $pHost = ""; try { $pHost = ([uri]$iframe).Host } catch { $pHost = "" }
    $pIp   = if ($pHost) { Resolve-Ip $pHost } else { $null }
    $rx    = if ($pIp) { @("--resolve", "${pHost}:443:$pIp") } else { @() }
    $origin = "https://$pHost"

    $f1 = Join-Path $env:TEMP "izleai_iframe.html"
    & curl.exe -s -o $f1 -A $ua -e $main @rx --max-time 30 --compressed $iframe
    $page = [System.IO.File]::ReadAllText($f1)
    $pm = [regex]::Match($page, "openPlayer\('([^']+)'\s*,")
    if ($pm.Success) {
        $pl = [uri]::EscapeDataString($pm.Groups[1].Value)
        $f2 = Join-Path $env:TEMP "izleai_source2.json"
        & curl.exe -s -o $f2 -A $ua -e $iframe @rx --max-time 30 --compressed "$origin/source2.php?v=$pl"
        try {
            $sj = [System.IO.File]::ReadAllText($f2) | ConvertFrom-Json
            $file = $sj.playlist[0].sources[0].file
            if ($file) {
                $hls = $file.Replace("m.php", "master.m3u8")
                $f3 = Join-Path $env:TEMP "izleai.m3u8"
                $hHost = ""; try { $hHost = ([uri]$hls).Host } catch { $hHost = "" }
                $hIp   = if ($hHost) { Resolve-Ip $hHost } else { $null }
                $hrx   = if ($hIp) { @("--resolve", "${hHost}:443:$hIp") } else { @() }
                $hc = & curl.exe -s -o $f3 -w "%{http_code}" -A $ua -e "$origin/" @hrx --max-time 30 --compressed $hls
                $body = [System.IO.File]::ReadAllText($f3)
                $streamOk = ("$hc" -eq "200" -and $body.StartsWith("#EXTM3U"))
                $info = "origin=$pHost http=$hc bytes=$($body.Length)"
            }
        } catch { $info = "source2 parse error" }
    } else { $info = "openPlayer() not found" }
} else { $info = "iframe not found in secureData" }
$results += [pscustomobject]@{ Step="loadLinks"; Ok=$streamOk; Info=$info }

""
$results | Format-Table Step,Ok,Info -AutoSize | Out-String -Width 200 | Write-Output
if (@($results | Where-Object { -not $_.Ok }).Count -gt 0) { exit 1 }
Write-Output "IzleAI chain: ALL OK"
