# check-tokens.ps1 <url> <provider> : fetch url, extract load() tokens, print found/missing
param([string]$Url, [string]$Name)
$ErrorActionPreference = "SilentlyContinue"
$root = Split-Path $PSScriptRoot -Parent
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

$kt = Get-ChildItem (Join-Path (Join-Path $root $Name) "src\main\kotlin") -Recurse -Filter *.kt |
      Where-Object { $_.BaseName -eq $Name } | Select-Object -First 1
$code = [System.IO.File]::ReadAllText($kt.FullName)

$i = $code.IndexOf("suspend fun load(")
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
$tokens = @($tokens | Sort-Object -Unique)

$h = ([uri]$Url).Host
$ip = (Resolve-DnsName $h -Server 8.8.8.8 -Type A | Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress
$file = Join-Path $env:TEMP ("tok_" + $Name + ".html")
$c = curl.exe -s -o $file -w "%{http_code}" --resolve "${h}:443:$ip" -A $ua -e $Url -L --max-time 30 --compressed $Url
$html = [System.IO.File]::ReadAllText($file)

$found = 0; $missing = @()
foreach ($t in $tokens) {
    if ($html -match ('(?<![\w-])' + [regex]::Escape($t) + '(?![\w-])')) { $found++ } else { $missing += $t }
}
Write-Output ("{0}  http={1}  len={2}  tokens={3}/{4}" -f $Name, $c, $html.Length, $found, $tokens.Count)
Write-Output ("missing: " + ($missing -join ","))
