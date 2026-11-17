version = 6

cloudstream {
    authors     = listOf("BTcozum", "keyiflerolsun")
    language    = "tr"
    description = "1080P yabancı film izle, Türkçe dublaj ve Türkçe altyazılı film seçenekleri ile Türkiye'nin en geniş film arşivi 720pizle Full hd film izle."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 0 // 2026-11: eski API (/api/bg/*) 404, Next.js yeni tema - tam rewrite gerekli
    tvTypes = listOf("Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=selcukflix.app&sz=%size%"
}