package com.btcozum.btvault

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KultFilmlerPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KultFilmler())
    }
}
