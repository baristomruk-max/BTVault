version = 17

cloudstream {
    authors     = listOf("BTcozum", "keyiflerolsun")
    language    = "tr"
    description = "Dizilla tÃ¼m yabancÄ± dizileri Ã¼cretsiz olarak TÃ¼rkÃ§e Dublaj ve altyazÄ±lÄ± seÃ§enekleri ile 1080P kalite izleyebileceÄŸiniz yeni nesil yabancÄ± dizi izleme siteniz."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 0 // 2026-11-06: dizilla.la yeni Next.js tema; oynatici API'si yeniden yazilmali
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=dizilla.la&sz=%size%"
}