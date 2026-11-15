param([string]$Name,[string]$Url,[string]$Out)
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
$u = [uri]$Url; $h = $u.Host; $port = 443; if ($u.Scheme -eq 'http') { $port = 80 }
$ip = (Resolve-DnsName $h -Server 8.8.8.8 -Type A -ErrorAction Stop | Where-Object IPAddress | Select-Object -First 1).IPAddress
if (-not $ip) { Write-Output "NXDOMAIN"; exit 1 }
if (-not $Out) { $Out = Join-Path $PSScriptRoot ("_fetch_" + $Name + ".html") }
$r = curl.exe -s -o $Out -w "%{http_code}|%{url_effective}|%{size_download}" --resolve "${h}:${port}:$ip" -A $ua -L --max-time 30 --compressed $Url 2>$null
Write-Output "$Name $r -> $Out"
