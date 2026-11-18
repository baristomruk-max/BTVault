package com.btcozum.btvault

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class AsyaWatch : MainAPI() {
    override var mainUrl = "https://asyawatch.com"
    override var name = "AsyaWatch"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Ana Sayfa"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        // Sadece gerçek dizi kartları: b etiketli (a.sc/a.nwc/a.pcard) veya dogrudan baslikli (a.tcn/a.ct)
        val selector = "a.sc, a.nwc, a.tcn, a.ct, a.sc.pcard, a.pcard"
        val items = document.select(selector).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/dizi/")) return null
        // a.sc.pcard: title in b, poster in img
        // a.tcn: title in text, poster in sibling a.tci img
        val title = this.selectFirst("b")?.text()?.trim()
            ?: this.text().trim().takeIf { it.isNotBlank() && it != "?" }
            ?: this.parent()?.selectFirst("a.tcn")?.text()?.trim()
            ?: return null
        if (title.isBlank() || title == "?") return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
            ?: fixUrlNull(this.parent()?.selectFirst("a.tci img, img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/ara?q=${query}").document
        return document.select("a.sc.pcard, a.pcard, #dzm-series a[href*=\"/dizi/\"]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val ldJson = document.selectFirst("script[type=\"application/ld+json\"]")?.data() ?: ""
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val ld = try { mapper.readValue(ldJson, Map::class.java) as Map<String, Any> } catch (_: Exception) { emptyMap() }
        val title = ld["name"]?.toString() ?: document.selectFirst("title")?.text()?.substringBefore(" izle") ?: return null
        val description = ld["description"]?.toString()
        val posterUrl = fixUrlNull(ld["image"]?.toString())
        val episodes = mutableListOf<Episode>()
        val seasons = ld["containsSeason"] as? List<*> ?: emptyList<Any?>()
        seasons.forEach { season ->
            val sm = season as? Map<*, *> ?: return@forEach
            val seasonNum = sm["seasonNumber"]?.toString()?.toIntOrNull() ?: 1
            val eps = sm["episode"] as? List<*> ?: emptyList<Any?>()
            eps.forEach { ep ->
                val em = ep as? Map<*, *> ?: return@forEach
                val epName = em["name"]?.toString() ?: return@forEach
                val epUrl = fixUrlNull(em["url"]?.toString()) ?: return@forEach
                val epNum = em["episodeNumber"]?.toString()?.toIntOrNull()
                episodes.add(newEpisode(epUrl) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                })
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data).text

        // 2026-11: kaynaklar four.pichive.online/iframe.php uzerinde (NP player + proof-of-work kapisi)
        val iframes = Regex("""https?://[A-Za-z0-9.\-]+/iframe\.php\?v=[A-Za-z0-9_\-]+""")
            .findAll(html)
            .map { it.value }
            .distinct()
            .toList()

        var resolved = false
        for (iframe in iframes) {
            if (AsyaWatchPichive.resolve(iframe, subtitleCallback, callback)) resolved = true
        }
        if (resolved) return true

        // Yedek: sayfada dogrudan m3u8 varsa
        val direct = Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""").find(html)?.value
            ?: return false
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = direct,
                ExtractorLinkType.M3U8
            ) {
                this.referer = data
            }
        )
        return true
    }
}
