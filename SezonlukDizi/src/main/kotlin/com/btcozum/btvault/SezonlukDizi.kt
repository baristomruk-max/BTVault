package com.btcozum.btvault

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup


class SezonlukDizi : MainAPI() {
    override var mainUrl              = "https://sezonlukdizi.cc"
    override var name                 = "SezonlukDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override var supportedTypes       = setOf(TvType.TvSeries)

    // the search call (diziler.asp?adi=...) sits behind a Cloudflare challenge
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())
            if (doc.text().contains("Just a moment...")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler.asp?siralama_tipi=id&s="          to "Son Eklenenler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=mini&s=" to "Mini Diziler",
        "${mainUrl}/diziler.asp?kat=2&s="                     to "Yerli Diziler",
        "${mainUrl}/diziler.asp?kat=1&s="                     to "Yabanci Diziler",
        "${mainUrl}/diziler.asp?kat=3&s="                     to "Asya Dizileri",
        "${mainUrl}/diziler.asp?kat=4&s="                     to "Animasyonlar",
        "${mainUrl}/diziler.asp?kat=5&s="                     to "Animeler",
        "${mainUrl}/diziler.asp?kat=6&s="                     to "Belgeseller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("div.afis a[href*='/diziler/']").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.description")?.text()?.trim()
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/diziler.asp?adi=${query}", interceptor = interceptor).document
        return document.select("div.afis a[href*='/diziler/']").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title       = document.selectFirst("div.header")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("[property='og:title']")?.attr("content")?.trim()
            ?: return null
        val poster      = fixUrlNull(
            document.selectFirst("div.image img")?.attr("data-src")
                ?: document.selectFirst("div.image img")?.attr("src")
                ?: document.selectFirst("[property='og:image']")?.attr("content")
        ) ?: return null
        val year        = document.selectFirst("div.extra span")?.text()?.trim()?.split("-")?.first()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(document.select("div.extra.content").text())?.value?.toIntOrNull()
        val description = document.selectFirst("span#tartismayorum-konu")?.text()?.trim()
        val tags        = document.select("div.labels a[href*='tur']").mapNotNull { it.text().trim() }
        val duration    = document.selectXpath("//span[contains(text(), 'Dk.')]").text().trim().substringBefore(" Dk.").toIntOrNull()
        val endpoint    = url.split("/").last()
        val actorsReq   = app.get("${mainUrl}/oyuncular/${endpoint}").document
        val actors      = actorsReq.select("div.doubling div.ui").mapNotNull {
            val name = it.selectFirst("div.header")?.text()?.trim() ?: return@mapNotNull null
            Actor(name, fixUrlNull(it.selectFirst("img")?.attr("src")))
        }
        val episodesReq = app.get("${mainUrl}/bolumler/${endpoint}").document
        val episodes    = mutableListOf<Episode>()
        for (sezon in episodesReq.select("table.unstackable")) {
            for (bolum in sezon.select("tbody tr")) {
                val epHref = fixUrlNull(bolum.selectFirst("td:nth-of-type(4) a")?.attr("href")
                    ?: bolum.selectFirst("td:nth-of-type(3) a")?.attr("href")) ?: continue
                val epName = bolum.selectFirst("td:nth-of-type(4) a")?.text()?.trim() ?: continue
                val epEpisode = Regex("""(\d+)""").find(
                    bolum.selectFirst("td:nth-of-type(3)")?.text() ?: ""
                )?.groupValues?.get(1)?.toIntOrNull()
                val epSeason = Regex("""(\d+)""").find(
                    bolum.selectFirst("td:nth-of-type(2)")?.text() ?: ""
                )?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epHref) { this.name = epName; this.season = epSeason; this.episode = epEpisode })
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.duration = duration; this.actors = actors.map { ActorData(it) }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val bid      = document.selectFirst("div#dilsec")?.attr("data-id") ?: return false

        val altyaziResponse = app.post("${mainUrl}/ajax/dataAlternatif.asp", headers = mapOf("X-Requested-With" to "XMLHttpRequest"), data = mapOf("bid" to bid, "dil" to "1")).parsedSafe<Kaynak>()
        altyaziResponse?.takeIf { it.status == "success" }?.data?.forEach { veri ->
            val veriResponse = app.post("${mainUrl}/ajax/dataEmbed.asp", headers = mapOf("X-Requested-With" to "XMLHttpRequest"), data = mapOf("id" to "${veri.id}")).document
            val iframe = fixUrlNull(veriResponse.selectFirst("iframe")?.attr("src")) ?: return@forEach
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback) { link ->
                callback.invoke(ExtractorLink(source = "AltYazi - ${veri.baslik}", name = "AltYazi - ${veri.baslik}", url = link.url, referer = link.referer, quality = link.quality, headers = link.headers, extractorData = link.extractorData, type = link.type))
            }
        }

        val dublajResponse = app.post("${mainUrl}/ajax/dataAlternatif.asp", headers = mapOf("X-Requested-With" to "XMLHttpRequest"), data = mapOf("bid" to bid, "dil" to "0")).parsedSafe<Kaynak>()
        dublajResponse?.takeIf { it.status == "success" }?.data?.forEach { veri ->
            val veriResponse = app.post("${mainUrl}/ajax/dataEmbed.asp", headers = mapOf("X-Requested-With" to "XMLHttpRequest"), data = mapOf("id" to "${veri.id}")).document
            val iframe = fixUrlNull(veriResponse.selectFirst("iframe")?.attr("src")) ?: return@forEach
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback) { link ->
                callback.invoke(ExtractorLink(source = "Dublaj - ${veri.baslik}", name = "Dublaj - ${veri.baslik}", url = link.url, referer = link.referer, quality = link.quality, headers = link.headers, extractorData = link.extractorData, type = link.type))
            }
        }
        return true
    }

    data class Kaynak(val status: String = "", val data: List<KaynakData>? = null)
    data class KaynakData(val id: String = "", val baslik: String = "")
    data class AspData(val alternatif: String, val embed: String)
}
