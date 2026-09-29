@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.btcozum.btvault

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class FilmModu : MainAPI() {
    override var mainUrl              = "https://www.filmmodu.one"
    override var name                 = "FilmModu"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/film-tur/4k-film-izle"          to "4K",
        "${mainUrl}/film-tur/aile-filmleri"         to "Aile",
        "${mainUrl}/film-tur/aksiyon"               to "Aksiyon",
        "${mainUrl}/film-tur/animasyon"             to "Animasyon",
        "${mainUrl}/film-tur/belgeseller"           to "Belgesel",
        "${mainUrl}/film-tur/bilim-kurgu-filmleri"  to "Bilim-Kurgu",
        "${mainUrl}/film-tur/dram-filmleri"         to "Dram",
        "${mainUrl}/film-tur/fantastik-filmler"     to "Fantastik",
        "${mainUrl}/film-tur/gerilim"               to "Gerilim",
        "${mainUrl}/film-tur/gizem-filmleri"        to "Gizem",
        "${mainUrl}/film-tur/hd-hint-filmleri"      to "Hint Filmleri",
        "${mainUrl}/film-tur/kisa-film"             to "Kısa Film",
        "${mainUrl}/film-tur/hd-komedi-filmleri"    to "Komedi",
        "${mainUrl}/film-tur/korku-filmleri"        to "Korku",
        "${mainUrl}/film-tur/kult-filmler-izle"     to "Kült Filmler",
        "${mainUrl}/film-tur/macera-filmleri"       to "Macera",
        "${mainUrl}/film-tur/muzik"                 to "Müzik",
        "${mainUrl}/film-tur/odullu-filmler-izle"   to "Oscar Ödüllü Filmler",
        "${mainUrl}/film-tur/romantik-filmler"      to "Romantik",
        "${mainUrl}/film-tur/savas-filmleri"        to "Savaş",
        "${mainUrl}/film-tur/stand-up"              to "Stand Up",
        "${mainUrl}/film-tur/suc-filmleri"          to "Suç",
        "${mainUrl}/film-tur/tarih"                 to "Tarih",
        "${mainUrl}/film-tur/tavsiye-filmler"       to "Tavsiye Filmler",
        "${mainUrl}/film-tur/tv-film"               to "TV film",
        "${mainUrl}/film-tur/vahsi-bati-filmleri"   to "Vahşi Batı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // "?page=1" adresi bos sayfa donuyor, ilk sayfa parametresiz istek edilmeli
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val document = app.get(url).document
        val home     = document.select("div.movie").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val aTag       = selectFirst("a") ?: return null
        val title     = aTag.text().trim().ifEmpty { selectFirst("img")?.attr("alt")?.trim() ?: "" }
        if (title.isEmpty()) return null
        val href      = fixUrlNull(aTag.attr("href")) ?: return null
        // afis lazy yukleniyor: gercek adres data-src icinde
        val posterUrl = fixUrlNull(
            selectFirst("picture img")?.attr("data-src")
                ?: selectFirst("picture img")?.attr("src")
                ?: selectFirst("img")?.attr("data-src")
        )

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/film-ara?term=${query}").document

        return document.select("div.movie").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Yeni tema schema.org microdata kullanıyor (div.titles / div.description kalkti)
        val orgTitle    = document.selectFirst("h1[itemprop='name']")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        val altTitle    = document.selectFirst("h2[itemprop='alternateName']")?.text()?.trim() ?: ""
        val title       = if (altTitle.isNotEmpty()) "$orgTitle - $altTitle" else orgTitle
        val poster      = fixUrlNull(
            document.selectFirst("img[itemprop='image']")?.attr("src")
                ?: document.selectFirst("img[itemprop='image']")?.attr("data-src")
                ?: document.selectFirst("img.img-responsive")?.attr("src")
        )
        val description = document.selectFirst("[itemprop='description']")?.text()?.trim()
        val year        = document.selectFirst("[itemprop='dateCreated']")?.text()?.trim()?.toIntOrNull()
        val rating      = document.selectFirst("[itemprop='ratingValue']")?.text()?.trim()?.toRatingInt() ?: 0
        val tags        = document.select("div.genres a, a[href*='/film-tur/']").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct()
        val actors      = document.select("[itemprop='actor']").mapNotNull { el ->
            val name = el.attr("content").ifBlank { el.text() }.trim()
            if (name.isEmpty()) null else Actor(name)
        }
        val trailer     = document.selectFirst("div.container iframe")?.attr("src")
            ?: document.selectFirst("iframe[src*='youtube']")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.rating    = rating
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FLMMD", "data » $data")
        val document = app.get(data).document

        document.select("div.alternates a").forEach {
            val altLink = fixUrlNull(it.attr("href")) ?: return@forEach
            val altName = it.text()
            if (altName == "Fragman") return@forEach

            val altReq  = app.get(altLink)
            val vidId   = Regex("""var videoId = '(.*)'""").find(altReq.text)?.groupValues?.get(1) ?: return@forEach
            val vidType = Regex("""var videoType = '(.*)'""").find(altReq.text)?.groupValues?.get(1) ?: return@forEach

            val vidReq = app.get("${mainUrl}/get-source?movie_id=${vidId}&type=${vidType}").parsedSafe<GetSource>() ?: return@forEach

            if (vidReq.subtitle != null) {
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = "Türkçe",
                        url  = fixUrl(vidReq.subtitle)
                    )
                )
            }

            vidReq.sources?.forEach { source ->
                callback.invoke(
                    ExtractorLink(
                        source  = "${this.name} - $altName",
                        name    = "${this.name} - $altName",
                        url     = fixUrl(source.src),
                        referer = "${mainUrl}/",
                        quality = getQualityFromName(source.label),
                        type    = INFER_TYPE
                    )
                )
            }
        }

        return true
    }
}