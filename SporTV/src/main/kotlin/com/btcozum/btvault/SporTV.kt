@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.btcozum.btvault

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

/**
 * Dogrulanmis canli spor kanallari.
 *
 * Kaynaklar iptv-org veritabanindan secilip tek tek test edildi:
 * master playlist -> variant playlist -> ilk segment zinciri 200/206 donuyor.
 *
 * Not: beIN Sports ve Eurosport yayinlari bazi ISS'lerde DNS seviyesinde
 * engellidir. README'deki DNS cozumu uygulaninca sorunsuz acilir.
 */
class SporTV : MainAPI() {
    override var mainUrl              = "https://iptv-org.github.io/iptv/categories/sports.m3u"
    override var name                 = "SporTV"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = false
    override val supportedTypes       = setOf(TvType.Live)

    private val browserUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val channels = listOf(
        // ---------------------------------------------------------------- Turkiye
        SporChannel(
            "TRT Spor", "Turkiye Spor",
            "https://tv-trtspor1.medya.trt.com.tr/master.m3u8",
            "https://i.imgur.com/6tv0zxh.png"
        ),
        SporChannel(
            "TRT Spor Yildiz", "Turkiye Spor",
            "https://trt.daioncdn.net/trtspor-yildiz/master.m3u8?app=web&platform=trtspor",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/c/ce/TRT_Spor_Y%C4%B1ld%C4%B1z_Logo.svg/960px-TRT_Spor_Y%C4%B1ld%C4%B1z_Logo.svg.png"
        ),
        SporChannel(
            "A Spor", "Turkiye Spor",
            "https://rnttwmjcin.turknet.ercdn.net/lcpmvefbyo/aspor/aspor.m3u8",
            "https://i.imgur.com/ZhkZzLf.png"
        ),
        SporChannel(
            "beIN Sports 1", "Turkiye Spor",
            "https://andro.mangeinde4.lat/checklist/androstreamlivebs1.m3u8",
            "https://i.imgur.com/Vtk2cGI.png"
        ),
        SporChannel(
            "beIN Sports 2", "Turkiye Spor",
            "https://andro.mangeinde4.lat/checklist/androstreamlivebs2.m3u8",
            "https://i.imgur.com/vUJZSvs.png"
        ),
        SporChannel(
            "beIN Sports 3", "Turkiye Spor",
            "https://andro.mangeinde4.lat/checklist/androstreamlivebs3.m3u8",
            "https://i.imgur.com/UYSMao3.png"
        ),
        SporChannel(
            "beIN Sports 4", "Turkiye Spor",
            "https://andro.mangeinde4.lat/checklist/androstreamlivebs4.m3u8",
            "https://i.imgur.com/vwAgJNi.png"
        ),
        SporChannel(
            "beIN Sports 5", "Turkiye Spor",
            "https://andro.mangeinde4.lat/checklist/androstreamlivebs5.m3u8",
            "https://i.imgur.com/2Rha5aY.png"
        ),
        SporChannel(
            "beIN Sports Haber", "Turkiye Spor",
            "https://andro.mangeinde4.lat/checklist/androstreamlivebsh.m3u8",
            "https://i.imgur.com/QWo0x7S.jpg"
        ),
        SporChannel(
            "Eurosport 1", "Turkiye Spor",
            "https://andro.mangeinde4.lat/checklist/androstreamlivees1.m3u8",
            "https://i.imgur.com/olQJgm7.png"
        ),
        SporChannel(
            "Eurosport 2", "Turkiye Spor",
            "https://andro.mangeinde4.lat/checklist/androstreamlivees2.m3u8",
            "https://i.imgur.com/f56dHgR.png"
        ),
        SporChannel(
            "HT Spor", "Turkiye Spor",
            "https://ciner.daioncdn.net/ht-spor/ht-spor.m3u8?app=web",
            "https://www.google.com/s2/favicons?domain=htspor.com&sz=256"
        ),
        SporChannel(
            "FB TV", "Turkiye Spor",
            "http://1hskrdto.rocketcdn.com/fenerbahcetv.smil/playlist.m3u8",
            "https://i.imgur.com/2Lge7vA.jpeg"
        ),
        SporChannel(
            "TJK TV", "Turkiye Spor",
            "https://tjktv-live.tjk.org/tjktv.m3u8",
            "https://i.imgur.com/3zHdkYG.png"
        ),
        SporChannel(
            "TJK TV 2", "Turkiye Spor",
            "https://tjktv-live.tjk.org/tjktv2/tjktv2.m3u8",
            "https://i.imgur.com/3zHdkYG.png"
        ),

        // ---------------------------------------------------------------- Dunya
        SporChannel(
            "beIN Sports XTRA", "Dunya Spor",
            "https://bein-xtra-bein.amagi.tv/playlist.m3u8",
            "https://i.ibb.co/HT49GPmB/XTRA-2.png"
        ),
        SporChannel(
            "Eurosport", "Dunya Spor",
            "https://drk6xq0vhn.gpcdn.net/live/euro_sports_hd_abr/index.m3u8",
            "https://i.imgur.com/olQJgm7.png"
        ),
        SporChannel(
            "NBA TV", "Dunya Spor",
            "http://23.237.104.106:8080/USA_NBA/index.m3u8",
            "https://upload.wikimedia.org/wikipedia/en/thumb/d/d2/NBA_TV.svg/960px-NBA_TV.svg.png"
        ),
        SporChannel(
            "WWE Network", "Dunya Spor",
            "http://103.151.60.162:2122/play/a00p/index.m3u8?hls",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/8/88/WWE_Network_logo.svg/500px-WWE_Network_logo.svg.png"
        ),
        SporChannel(
            "Formula 1", "Dunya Spor",
            "https://live4dai.antenaplay.ro/live2_sdi09/live2_sdi09_600k.m3u8",
            "https://www.google.com/s2/favicons?domain=antenaplay.ro&sz=256"
        ),
        SporChannel(
            "FightBox", "Dunya Spor",
            "https://webtvstream.bhtelecom.ba/fightbox.m3u8",
            "https://i.imgur.com/mRsnztA.png"
        ),
        SporChannel(
            "FightBox HD", "Dunya Spor",
            "https://nosignal1.antenaplay.ro/hls/fightbox-hd/index.m3u8",
            "https://i.imgur.com/mRsnztA.png"
        ),
        SporChannel(
            "Golf Channel", "Dunya Spor",
            "https://dash4.antik.sk/live/test_golf_channel/playlist.m3u8",
            "https://www.sms.cz/kategorie/televize/bmp/loga/velka/golfchannel.png"
        ),
        SporChannel(
            "Tennis Channel", "Dunya Spor",
            "https://cdn-ue1-prod.tsv2.amagi.tv/linear/amg01444-tennischannelth-tennischannelnl-samsungnl/playlist.m3u8",
            "https://i.imgur.com/tsljAnY.png"
        ),
        SporChannel(
            "Tennis Channel 2", "Dunya Spor",
            "https://mumbai-edge.smartplaytv.in/Tennis2/index.m3u8",
            "https://i.imgur.com/tsljAnY.png"
        ),
        SporChannel(
            "Trace Sport Stars", "Dunya Spor",
            "https://channels.trace.plus/Traceprod/TRACE_SPORT_STARS_sd/index.m3u8",
            "https://i.imgur.com/FabFP5A.png"
        ),
        SporChannel(
            "Sport1", "Dunya Spor",
            "http://88.212.15.17/live/test_sport1_25p/playlist.m3u8",
            "https://www.google.com/s2/favicons?domain=sport1.de&sz=256"
        ),
        SporChannel(
            "Sport2", "Dunya Spor",
            "http://88.212.15.17/live/test_sport_2/playlist.m3u8",
            "https://www.google.com/s2/favicons?domain=sport1.de&sz=256"
        )
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val listeler = channels.groupBy { it.group }.map { (grup, kanallar) ->
            HomePageList(
                grup,
                kanallar.map { it.toSearchResponse() },
                isHorizontalImages = true
            )
        }
        return newHomePageResponse(listeler, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return channels
            .filter { it.title.contains(query, ignoreCase = true) }
            .map { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val veri = parseJson<LoadData>(url)

        val oneriler = channels
            .filter { it.group == veri.group && it.title != veri.title }
            .map { it.toSearchResponse() }

        return newLiveStreamLoadResponse(veri.title, veri.url, url) {
            this.posterUrl       = veri.logo
            this.plot            = "${veri.group} - canli yayin"
            this.tags            = listOf(veri.group)
            this.recommendations = oneriler
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val veri = parseJson<LoadData>(data)

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = veri.title,
                url    = veri.url,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = ""
                this.headers = mapOf("User-Agent" to browserUa)
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }

    private fun SporChannel.toLoadData() = LoadData(url, title, logo, group)

    private fun SporChannel.toSearchResponse(): LiveSearchResponse =
        newLiveSearchResponse(title, toLoadData().toJson(), type = TvType.Live) {
            this.posterUrl = logo
            this.lang      = "tr"
        }

    data class LoadData(
        val url: String,
        val title: String,
        val logo: String,
        val group: String
    )

    data class SporChannel(
        val title: String,
        val group: String,
        val url: String,
        val logo: String
    )
}
