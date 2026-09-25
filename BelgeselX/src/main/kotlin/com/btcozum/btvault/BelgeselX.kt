@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.btcozum.btvault

import java.util.Locale
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * belgeselx.com yeniden tasarlandi (2026).
 *
 *  liste   : /konu/<slug>  -> a.px-card  (sayfa 1 SSR, devami /ajax_konukat.php?url=<slug>&page=N)
 *  dizi    : /belgeseldizi/<slug> -> h1.px-hero-title + a.px-ep-card (butonKaydet('id'))
 *  izleme  : /belgesel/<slug>     -> diziGetir('id','ic1','ic2','ic3',...) cagrilari
 *  kaynak  : /video/data/<dosya>.php?id=<id>&sira=<1..3> -> jwplayer file: veya <iframe>
 */
class BelgeselX : MainAPI() {
    override var mainUrl              = "https://belgeselx.com"
    override var name                 = "BelgeselX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Documentary)

    override val mainPage = mainPageOf(
        "${mainUrl}/konu/turk-tarihi-belgeselleri" to "Türk Tarihi",
        "${mainUrl}/konu/tarih-belgeselleri"       to "Tarih",
        "${mainUrl}/konu/seyehat-belgeselleri"     to "Seyahat",
        "${mainUrl}/konu/seri-belgeseller"         to "Seri",
        "${mainUrl}/konu/savas-belgeselleri"       to "Savaş",
        "${mainUrl}/konu/sanat-belgeselleri"       to "Sanat",
        "${mainUrl}/konu/psikoloji-belgeselleri"   to "Psikoloji",
        "${mainUrl}/konu/polisiye-belgeselleri"    to "Polisiye",
        "${mainUrl}/konu/otomobil-belgeselleri"    to "Otomobil",
        "${mainUrl}/konu/nazi-belgeselleri"        to "Nazi",
        "${mainUrl}/konu/muhendislik-belgeselleri" to "Mühendislik",
        "${mainUrl}/konu/kultur-din-belgeselleri"  to "Kültür Din",
        "${mainUrl}/konu/kozmik-belgeseller"       to "Kozmik",
        "${mainUrl}/konu/hayvan-belgeselleri"      to "Hayvan",
        "${mainUrl}/konu/eski-tarih-belgeselleri"  to "Eski Tarih",
        "${mainUrl}/konu/egitim-belgeselleri"      to "Eğitim",
        "${mainUrl}/konu/dunya-belgeselleri"       to "Dünya",
        "${mainUrl}/konu/doga-belgeselleri"        to "Doğa",
        "${mainUrl}/konu/bilim-belgeselleri"       to "Bilim",
        "${mainUrl}/konu/cizgi-film"               to "Çizgi Film"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val slug = request.data.substringAfterLast("/")
        // 1. sayfa sunucuda render ediliyor, sonraki sayfalar ajax kirpisi donuyor
        val url  = if (page <= 1) "$mainUrl/konu/$slug"
                   else "$mainUrl/ajax_konukat.php?url=${slug}&page=${page}"

        val document = app.get(url).document
        val home     = document.select("a.px-card").mapNotNull { it.toSearchResult() }

        // ajax kirpisinda sayac yok; bos donene kadar devam et
        val pages   = document.selectFirst("button.px-load-btn")?.attr("data-pages")?.toIntOrNull()
        val hasNext = if (pages != null) page < pages else home.isNotEmpty()

        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun String.toTitleCase(): String {
        val locale = Locale("tr", "TR")
        return this.split(" ").joinToString(" ") { word ->
            word.lowercase(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.px-card-title")?.text()?.trim()?.toTitleCase() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.px-card-img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.Documentary) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cx = "016376594590146270301:iwmy65ijgrm" // ! Might change in the future
        val q  = java.net.URLEncoder.encode(query, "UTF-8")

        val tokenResponse  = app.get("https://cse.google.com/cse.js?cx=${cx}")
        val cseLibVersion  = Regex("""cselibVersion": "(.*)"""").find(tokenResponse.text)?.groupValues?.get(1)
        val cseToken       = Regex("""cse_token": "(.*)"""").find(tokenResponse.text)?.groupValues?.get(1)
        if (cseLibVersion.isNullOrBlank() || cseToken.isNullOrBlank()) return emptyList()

        val response = app.get("https://cse.google.com/cse/element/v1?rsz=filtered_cse&num=100&hl=tr&source=gcsc&cselibv=${cseLibVersion}&cx=${cx}&q=${q}&safe=off&cse_tok=${cseToken}&oq=${q}&callback=google.search.cse.api9969&rurl=https%3A%2F%2Fbelgeselx.com%2F")
        val text     = response.text

        // her sonuc kendi blogunda: title -> url -> ogImage sirasi var
        val chunks = text.split("\"titleNoFormatting\": \"")
        if (chunks.size <= 1) return emptyList()

        val parsed  = mutableListOf<Triple<String, String, String?>>()
        val series  = mutableSetOf<String>()

        for (i in 1 until chunks.size) {
            val chunk  = chunks[i]
            val title  = chunk.substringBefore("\"").trim()
            val url    = URL_RX.find(chunk)?.groupValues?.get(1)?.trim()
            val poster = IMAGE_RX.find(chunk)?.groupValues?.get(1)

            if (title.isEmpty() || url.isNullOrBlank()) continue
            if (!url.contains("belgeselx.com")) continue

            parsed.add(Triple(title, url, poster))
            if (url.contains("/belgeseldizi/")) series.add(url.substringAfterLast("/"))
        }

        return parsed.mapNotNull { (rawTitle, url, poster) ->
            val slug = url.substringAfterLast("/")

            // hem /belgeseldizi/ hem /belgesel/ sonucu doner; ayni dizi iki kez listelenmesin
            if (!url.contains("/belgeseldizi/") && series.contains(slug)) return@mapNotNull null
            if (!url.contains("/belgeseldizi/") && !url.contains("/belgesel/")) return@mapNotNull null

            val title = rawTitle.substringBefore(" İzle")
                .substringBefore(" - belgeselx.com").trim().ifEmpty { rawTitle.trim() }.toTitleCase()

            newTvSeriesSearchResponse(title, url, TvType.Documentary) { this.posterUrl = poster }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)
        val document = response.document

        val cards = document.select("a.px-ep-card")

        val title = document.selectFirst("h1.px-hero-title")?.text()?.trim()?.toTitleCase()
            ?: document.title().substringBefore("İzle").substringBefore(" - belgeselx.com").trim()
                .toTitleCase().ifEmpty { null }
            ?: return null

        val poster = fixUrlNull(document.selectFirst("div.px-dizi-card-poster img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val description = document.select("div.px-dizi-card-body > p")
            .map { it.text().trim() }.filter { it.length > 30 }.maxByOrNull { it.length }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val tags = document.select("span.px-imdb-genre-tag").mapNotNull { it.text().trim().ifEmpty { null } }

        val episodes = if (cards.isNotEmpty()) episodesFromCards(cards)
                       else episodesFromWatch(response.text, url)
        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.Documentary, episodes) {
            this.posterUrl = poster
            this.plot      = description
            if (tags.isNotEmpty()) this.tags = tags
        }
    }

    /** Dizi sayfasindaki kartlar: her kart izleme sayfasina gider, `?epid=` bolum kimligini tasir. */
    private fun episodesFromCards(cards: List<Element>): List<Episode> {
        var counter = 0

        return cards.mapNotNull { card ->
            val epName = card.selectFirst("span.px-ep-title")?.text()?.trim() ?: return@mapNotNull null
            val href   = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            val id     = EP_ID.find(card.attr("onclick"))?.groupValues?.get(1) ?: return@mapNotNull null

            val se       = EP_SEASON.find(card.selectFirst("span.px-ep-s")?.text() ?: "")
            val season   = se?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episode  = se?.groupValues?.get(2)?.toIntOrNull() ?: (++counter)

            newEpisode("${href}?epid=${id}") {
                this.name    = epName
                this.season  = season
                this.episode = episode
            }
        }
    }

    /** Izleme sayfasi: bolum listesi JS cagrilari olarak gozukur. */
    private fun episodesFromWatch(html: String, pageUrl: String): List<Episode> {
        val base = pageUrl.substringBefore("?")

        return DIZI_GETIR.findAll(html).mapIndexed { index, match ->
            val group = match.groupValues
            val id    = group[1]

            newEpisode("${base}?epid=${id}") {
                this.name    = group[5].trim()
                this.season  = group[6].toIntOrNull() ?: 1
                this.episode = group[7].toIntOrNull() ?: (index + 1)
            }
        }.toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watch = data.substringBefore("?")
        val epid  = EP_PARAM.find(data)?.groupValues?.get(1)

        val html  = app.get(watch).text
        val calls = DIZI_GETIR.findAll(html).toList()
        if (calls.isEmpty()) return false

        val target = if (epid != null) calls.firstOrNull { it.groupValues[1] == epid } ?: calls.first()
                     else calls.first()

        val id  = target.groupValues[1]
        val ics = listOf(target.groupValues[2], target.groupValues[3], target.groupValues[4])

        var found = false

        for (index in 0..2) {
            val file  = SRC_MAP[ics[index]] ?: "default"
            val src   = "${mainUrl}/video/data/${file}.php?id=${id}&sira=${index + 1}"
            val page  = runCatching { app.get(src, referer = watch).text }.getOrNull() ?: continue

            // 1) jwplayer kaynaklari: file:"<mp4>", label:"720p"
            val sources = JW_SOURCE.findAll(page).map { it.groupValues[1].trim() to it.groupValues[2].trim() }
                .filter { it.first.startsWith("http") }.toList()

            if (sources.isNotEmpty()) {
                for ((link, label) in sources) {
                    callback(
                        newExtractorLink(
                            source = "BelgeselX",
                            name   = if (label.isEmpty()) "BelgeselX" else "BelgeselX ${label}",
                            url    = link,
                            type   = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getQualityFromName(label)
                        }
                    )
                    found = true
                }
                continue
            }

            // 2) iframe oynatici (dailymotion / ok.ru / yadi.sk / drive)
            val iframe = WRAP_IFRAME.findAll(page).map { it.groupValues[1].trim() }
                .firstOrNull { it.startsWith("http") && !AD_HOSTS.any { ad -> it.contains(ad) } }
                ?: continue

            val fixed = fixUrlNull(iframe) ?: continue
            if (loadExtractor(fixed, src, subtitleCallback, callback)) found = true
        }

        return found
    }

    private companion object {
        // a[href*=belgeseldizi] kartindaki bolum kimligi
        val EP_ID      = Regex("""butonKaydet\('(\d+)'\)""")
        // kart uzerindeki "S3 - B14" etiketi
        val EP_SEASON  = Regex("""S(\d+)\s*·\s*B(\d+)""")
        // izleme sayfasindaki diziGetir(id, ic1, ic2, ic3, ad, hit, tarih, sezon, bolum, ...)
        val DIZI_GETIR = Regex("""diziGetir\(\s*'(\d+)'\s*,\s*'(\d+)'\s*,\s*'(\d+)'\s*,\s*'(\d+)'\s*,\s*'([^']*)'\s*,\s*'[^']*'\s*,\s*'[^']*'\s*,\s*'(\d*)'\s*,\s*'(\d*)'""")
        // kaynak oynaticinin kurulumu
        val JW_SOURCE   = Regex("""file:\s*"([^"]*)"\s*,\s*label:\s*"([^"]*)"""")
        val WRAP_IFRAME = Regex("""<iframe[^>]*\ssrc="([^"]+)"""")
        val EP_PARAM    = Regex("""[?&]epid=(\d+)""")

        val URL_RX   = Regex(""""url": "([^"]+)"""")
        val IMAGE_RX = Regex(""""ogImage": "([^"]+)"""")

        // diziGetir icindeki ic -> kaynak dosyasi eslemesi (siteden okunur)
        val SRC_MAP = mapOf(
            "0" to "new5", "2" to "new1", "5" to "new4", "3" to "new2", "4" to "new3"
        )

        val AD_HOSTS = listOf(
            "googlesyndication", "googletagmanager", "doubleclick", "adservice",
            "facebook.com", "recaptcha", "adnxs", "criteo"
        )
    }
}
