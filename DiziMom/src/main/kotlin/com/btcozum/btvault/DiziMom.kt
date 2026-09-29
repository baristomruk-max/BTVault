@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.btcozum.btvault

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class DiziMom : MainAPI() {
    override var mainUrl              = "https://www.dizimom.help"
    override var name                 = "DiziMom"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/page/"        to "Son Bölümler",
        "${mainUrl}/yerli-dizi-izle/page/"     to "Yerli Diziler",
        "${mainUrl}/yabanci-dizi-izle/page/"   to "Yabancı Diziler",
        "${mainUrl}/tv-programlari-izle/page/" to "TV Programları",
        // "${mainUrl}/turkce-dublaj-diziler/page/"      to "Dublajlı Diziler",   // ! "Son Bölümler" Ana sayfa yüklenmesini yavaşlattığı için bunlar devre dışı bırakılmıştır..
        // "${mainUrl}/netflix-dizileri-izle/page/"      to "Netflix Dizileri",
        // "${mainUrl}/kore-dizileri-izle/page/"         to "Kore Dizileri",
        // "${mainUrl}/full-hd-hint-dizileri-izle/page/" to "Hint Dizileri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}/").document
        val home     = if (request.data.contains("/tum-bolumler/")) {
            document.select("div.episode-box").mapNotNull { it.sonBolumler() }
        } else {
            // Yeni temada kartlar: <div class="img"><a href=".../diziler/<slug>/"><img data-src alt=".. izle">
            document.select("a[href*='/diziler/']").mapNotNull { it.diziler() }
                .distinctBy { it.url }
        }

        return newHomePageResponse(request.name, home)
    }

    private suspend fun Element.sonBolumler(): SearchResponse? {
        // Yeni temada bolum kutusu icinde dizi linki + (varsa) bolum linki var
        val link     = this.select("a[href]").firstOrNull { !it.attr("href").contains("/diziler/") }
            ?: this.selectFirst("a[href*='/diziler/']")
            ?: return null
        val href      = fixUrlNull(link.attr("href")) ?: return null
        val img       = this.selectFirst("img")
        val title     = img?.attr("alt")?.trim()?.substringBefore(" izle")?.trim()
            ?: this.selectFirst("div.episode-name")?.text()?.substringBefore(" izle")?.trim()
            ?: return null
        if (title.isEmpty()) return null

        val posterUrl = fixUrlNull(img?.attr("data-src")) ?: fixUrlNull(img?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun Element.diziler(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/diziler/")) return null
        val img       = selectFirst("img")
        val title     = img?.attr("alt")?.trim()?.substringBefore(" izle")?.trim()
            ?: text().trim().substringBefore(" izle").trim()
        if (title.isEmpty()) return null
        val posterUrl = fixUrlNull(img?.attr("data-src")) ?: fixUrlNull(img?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("a[href*='/diziler/']").mapNotNull { it.diziler() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.title h1")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.category_image img")?.attr("src")) ?: return null
        val year        = document.selectXpath("//div[span[contains(text(), 'Yapım Yılı')]]").text().substringAfter("Yapım Yılı : ").trim().toIntOrNull()
        val description = document.selectFirst("div.category_desc")?.text()?.trim()
        val tags        = document.select("div.genres a").mapNotNull { it.text().trim() }
        val rating      = document.selectXpath("//div[span[contains(text(), 'IMDB')]]").text().substringAfter("IMDB : ").trim().toRatingInt()
        val actors      = document.selectXpath("//div[span[contains(text(), 'Oyuncular')]]").text().substringAfter("Oyuncular : ").split(", ").map {
            Actor(it.trim())
        }

        val episodes    = document.select("div.bolumust").mapNotNull {
            val epName    = it.selectFirst("div.baslik")?.text()?.trim() ?: return@mapNotNull null
            val epHref    = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val epEpisode = Regex("""(\d+)\.Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            val epSeason  = Regex("""(\d+)\.Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name    = epName.substringBefore(" izle").replace(title, "").trim()
                this.season  = epSeason
                this.episode = epEpisode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZM", "data » $data")

        val ua = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")

        app.post(
            "${mainUrl}/wp-login.php",
            headers = ua,
            referer = "${mainUrl}/",
            data    = mapOf(
                "log"         to "keyiflerolsun",
                "pwd"         to "12345",
                "rememberme"  to "forever",
                "redirect_to" to mainUrl,
            )
        )

        val document = app.get(data, headers=ua).document

        val iframes     = mutableListOf<String>()
        val mainIframe = document.selectFirst("div.video p iframe")?.attr("src") ?: return false
        iframes.add(mainIframe)

        document.select("div.sources a").forEach {
            val subDocument = app.get(it.attr("href"), headers=ua).document
            val subIframe   = subDocument.selectFirst("div.video p iframe")?.attr("src") ?: return@forEach

            iframes.add(subIframe)
        }

        for (iframe in iframes) {
            Log.d("DZM", "iframe » $iframe")
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }
}
