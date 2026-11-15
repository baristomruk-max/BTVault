$t = [System.Convert]::FromBase64String("5Ee4I16SxLnIVWnBwMMjpKJeoz4C3oLyy1s19MWWKITuULUGSe2K")
$n = [System.Convert]::FromBase64String("jDPMUy2o65auNBq1sK9C3Q==")
$result = ""
for ($c = 0; $c -lt $t.Length; $c++) {
    $result += [char]($t[$c] -bxor $n[$c % $n.Length])
}
Write-Output $result
