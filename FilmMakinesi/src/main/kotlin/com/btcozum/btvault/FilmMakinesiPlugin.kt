package com.btcozum.btvault

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FilmMakinesiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmMakinesi())
        // FilmMakinesi kendi oynaticilarini kullaniyor, hazir extractor yok
        registerExtractorAPI(CloseLoadExtractor())
        registerExtractorAPI(RapidFMExtractor())
    }
}
