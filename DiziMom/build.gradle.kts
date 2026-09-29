version = 11

cloudstream {
    authors     = listOf("BTcozum", "keyiflerolsun")
    language    = "tr"
    description = "Binlerce yerli yabancÄ± dizi arÅŸivi, tÃ¼m sezonlar, kesintisiz bÃ¶lÃ¼mler. Sadece dizi izle, Dizimom heryerde seninle!"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=www.dizimom.tv&sz=%size%"
}