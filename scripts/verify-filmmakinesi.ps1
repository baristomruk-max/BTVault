# FilmMakinesi canli dogrulama: film sayfasi -> closeload embed -> HLS master
# Not: site bazi datacenter/ASN'leri 403 (Cloudflare 1005) ile engelliyor.
# Bu yuzden once normal istek, gerekirse --resolve + Google DNS ile zorlanir.
$ErrorActionPreference = "SilentlyContinue"
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$tmp = Join-Path $PSScriptRoot "fm-verify"
if (-not (Test-Path $tmp)) { New-Item -ItemType Directory -Path $tmp | Out-Null }

function Get-Page([string]$url, [string]$referer = "") {
    $host_ = ([uri]$url).Host
    $ip = (Resolve-DnsName $host_ -Server 8.8.8.8 -Type A | Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress
    $out = Join-Path $tmp ($host_ + "-" + ([guid]::NewGuid().ToString("N").Substring(0, 6)) + ".html")
    $args = @("-s", "-o", $out, "-w", "%{http_code}", "-A", $ua, "-L", "--max-time", "30", "--compressed")
    if ($referer) { $args += @("-H", "Referer: $referer") }
    if ($ip) { $args += @("--resolve", "${host_}:443:$ip") }
    $args += $url
    $code = (& curl.exe @args)
    $body = if (Test-Path $out) { [System.IO.File]::ReadAllText($out) } else { "" }
    [pscustomobject]@{ Code = $code; Body = $body }
}

function Rev([string]$s) {
    if (!$s) { return $s }
    $a = $s.ToCharArray(); [array]::Reverse($a); -join $a
}
function DecodeB64([string]$s) {
    $clean = ($s -replace '[=\s]', '')
    if (-not $clean) { return "" }
    $pad = (4 - ($clean.Length % 4)) % 4
    [System.Text.Encoding]::GetEncoding(28591).GetString([Convert]::FromBase64String($clean + ("=" * $pad)))
}
function Decode-Payload([string[]]$parts) {
    $list = [System.Collections.ArrayList]$parts
    if ($list.Count -lt 3) { return "" }
    $a = $list.Count - 2
    $oz = $a % 7
    $ft = 8 + ($a % 5)
    if ($ft -ge $list.Count -or $oz -ge $list.Count) { return "" }
    $t2o = [string]$list[$ft]; $list.RemoveAt($ft)
    $xt = [string]$list[$oz]; $list.RemoveAt($oz)
    $uv = -join $list
    if ($xt.Length -gt 4096) { $uv = DecodeB64 $uv }

    $xb = 0; $hq = 0
    for ($i = 0; $i -lt $xt.Length; $i++) {
        $c = [int][char]$xt[$i]
        $xb = ($xb * 37 + $c) % 241
        $hq = ($hq + (($c -shl 1) -bxor $i)) -band 255
    }
    $ml = ($xb * 3 + $hq) % 256
    $gj = ($hq % 11) + 5
    $eu = ((($hq * 251 + $xb) % 65519) + 1)

    for ($i = $t2o.Length - 1; $i -ge 0; $i--) {
        $ch = $t2o[$i]
        if ($ch -eq '7') { $uv = DecodeB64 $uv }
        elseif ($ch -eq '3') { $uv = Rev $uv }
        else {
            $sh = (26 - ((([int][char]$ch) - 96) % 26)) % 26
            $sb = New-Object System.Text.StringBuilder
            foreach ($cc in $uv.ToCharArray()) {
                if ($cc -ge 'A' -and $cc -le 'Z') { [void]$sb.Append([char]((([int]$cc - 65 + $sh) % 26) + 65)) }
                elseif ($cc -ge 'a' -and $cc -le 'z') { [void]$sb.Append([char]((([int]$cc - 97 + $sh) % 26) + 97)) }
                else { [void]$sb.Append($cc) }
            }
            $uv = $sb.ToString()
        }
    }
    if ($t2o.Length -gt 2048) { $uv = Rev $uv }

    $len = $uv.Length
    if ($len -lt 2) { return "" }
    $sw = New-Object 'int[]' $len
    for ($i = $len - 1; $i -ge 1; $i--) { $eu = ($eu * 97 + 41) % 65519; $sw[$i] = $eu % ($i + 1) }
    $ch2 = $uv.ToCharArray()
    for ($i = 1; $i -lt $len; $i++) { $j = $sw[$i]; $t = $ch2[$i]; $ch2[$i] = $ch2[$j]; $ch2[$j] = $t }
    $uv = -join $ch2

    $st = $ml
    $out = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt $uv.Length; $i++) {
        $c = [int][char]$uv[$i]
        $st = ($st * 5 + $gj) % 256
        [void]$out.Append([char]($c -bxor $st))
        $st = ($st + $c) % 256
    }
    $out.ToString()
}

Write-Host "== 1) liste sayfasi =="
$list = Get-Page "https://filmmakinesi.to/filmler-1/"
if ($list.Code -ne "200") { Write-Host "SKIP: liste sayfasi $($list.Code)"; exit 0 }
$film = [regex]::Match($list.Body, 'href="(/film/[^"]+)"')
if (-not $film.Success) { Write-Host "FAIL: film linki bulunamadi"; exit 1 }
$filmUrl = "https://filmmakinesi.to" + $film.Groups[1].Value
Write-Host "OK film: $filmUrl"

Write-Host "== 2) film sayfasi =="
$page = Get-Page $filmUrl "https://filmmakinesi.to/"
if ($page.Code -ne "200") { Write-Host "SKIP: film sayfasi $($page.Code)"; exit 0 }
$embed = [regex]::Match($page.Body, '<iframe[^>]+data-src="(https?://[^"]+)"')
if (-not $embed.Success) { Write-Host "FAIL: oynatici iframe bulunamadi"; exit 1 }
$embedUrl = $embed.Groups[1].Value
Write-Host "OK embed: $embedUrl"

Write-Host "== 3) embed sayfasi =="
$emb = Get-Page $embedUrl "https://filmmakinesi.to/"
if ($emb.Code -ne "200") { Write-Host "SKIP: embed $($emb.Code)"; exit 0 }
$pay = [regex]::Match($emb.Body, 'sources:\s*\[\{\s*file:\s*([A-Za-z0-9_$]+)')
if (-not $pay.Success) { Write-Host "FAIL: sources bulunamadi"; exit 1 }
$varName = $pay.Groups[1].Value
$pat = "var\s+" + [regex]::Escape($varName) + "\s*=\s*[A-Za-z0-9_$]+\s*\(\s*`"([^`"]+)`"\s*\.split\(\s*`"([^`"]+)`"\s*\)\s*\)"
$m = [regex]::Match($emb.Body, $pat)
if (-not $m.Success) { Write-Host "FAIL: payload satiri bulunamadi ($varName)"; exit 1 }
$stream = Decode-Payload (($m.Groups[1].Value -replace '\\/', '/').Split($m.Groups[2].Value))
if (-not $stream.StartsWith("http")) { Write-Host "FAIL: stream cozulemedi"; exit 1 }
Write-Host "OK stream: $stream"

Write-Host "== 4) HLS master (referer gerekli) =="
$code = & curl.exe -s -o (Join-Path $tmp "master.txt") -w "%{http_code}" -A $ua --max-time 25 -H "Referer: $embedUrl" $stream
$head = (Get-Content (Join-Path $tmp "master.txt") -TotalCount 1)
if ($code -eq "200" -and $head -like "#EXTM3U*") { Write-Host "OK master playlist ($code)"; Write-Host "PASS" }
else { Write-Host "FAIL master: code=$code first=$head"; exit 1 }
