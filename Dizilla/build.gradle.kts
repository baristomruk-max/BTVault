version = 16

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
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=dizilla.club&sz=%size%"
}