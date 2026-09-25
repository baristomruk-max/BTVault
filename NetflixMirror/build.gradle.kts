version = 8

cloudstream {
    authors     = listOf("BTcozum", "keyiflerolsun")
    language    = "hi"
    description = "Netflix, PrimeVideo Content in Multiple Languages"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 0 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://iosmirror.cc/img/nf2/icon_x192.png"
}