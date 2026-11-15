Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue

# FNV-1a 32-bit hash (matches JavaScript Math.imul behavior)
function fnv1a($input) {
    $hash = 2166136261
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($input)
    foreach ($b in $bytes) {
        $hash = ($hash -bxor $b) -band 0xFFFFFFFF
        $product = [uint64]$hash * 16777619
        $hash = [uint32]($product -band 0xFFFFFFFF)
    }
    return $hash.ToString("x8")
}

# Generate X-Sp token exactly like JavaScript
function generate-XSp($sp, $spT, $offsetSeconds) {
    $n = $spT + $offsetSeconds
    
    # Math.floor(2176782336 * Math.random()).toString(36)
    $rng = New-Object System.Random
    $random = [Math]::Floor(2176782336 * $rng.NextDouble()).ToString("36")
    
    # fnv1a(sp + "|" + n + "|" + r)
    $hashInput = "$sp`|$n`|$random"
    $hash = fnv1a $hashInput
    
    # n + "." + r + "." + hash
    $token = "$n.$random.$hash"
    return $token
}

$sp = "196438ad8e98fe65a2579ae40cacc634"
$spT = 1794775982
$manifestUrl = "https://fastplay.mom/manifests/Au9jYbcyUdEa/master.txt?verify=1794765600-GkluWfwvLLE94hc4wYm452iDdrfMLkhUEH3WxETJIyE%3D"

# Try multiple offset values
for ($offset = -10; $offset -le 10; $offset++) {
    $token = generate-XSp $sp $spT $offset
    $result = curl.exe -s -o NUL -w "HTTP:%{http_code}" --connect-timeout 8 --max-time 15 -H "Referer: https://fastplay.mom/video/Au9jYbcyUdEa" -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36" -H "X-Sp: $token" "$manifestUrl"
    Write-Output "offset=$offset -> $result"
}
