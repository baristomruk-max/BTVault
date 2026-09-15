<p align="center">
  <img src=".github/assets/banner.svg" alt="BTVault Banner" width="100%"/>
</p>

<h1 align="center">BTVault</h1>

<p align="center">
  <strong>CloudStream Turkce Eklenti Deposu</strong><br>
  Film, dizi ve anime platformlari icin tek depo.
</p>

<p align="center">
  <a href="cloudstreamrepo://raw.githubusercontent.com/baristomruk-max/BTVault/master/repo.json">
    <img src="https://img.shields.io/badge/-Depoyu_Ekle-4CAF50?style=for-the-badge&logo=android&logoColor=white" alt="Depoyu Ekle"/>
  </a>
  <a href="https://github.com/recloudstream/cloudstream/releases/tag/pre-release">
    <img src="https://img.shields.io/badge/-CloudStream_Pre--Release-2196F3?style=for-the-badge&logo=github&logoColor=white" alt="CloudStream APK"/>
  </a>
  <a href="https://github.com/baristomruk-max/BTVault">
    <img src="https://img.shields.io/badge/-GitHub-181717?style=for-the-badge&logo=github&logoColor=white" alt="GitHub"/>
  </a>
</p>

---

## Tek Tikla Kurulum

> **Telefonunuzdan asagidaki butona tiklayin, CloudStream otomatik olarak acilacak ve depoyu ekleyecektir.**

<p align="center">
  <a href="cloudstreamrepo://raw.githubusercontent.com/baristomruk-max/BTVault/master/repo.json">
    <img src="https://img.shields.io/badge/TIKLA_-_Depoyu_Otomatik_Ekle-4CAF50?style=for-the-badge&logo=android&logoColor=white&labelColor=333" alt="Depoyu Ekle"/>
  </a>
</p>

### Manuel Kurulum

1. [CloudStream Pre-Release](https://github.com/recloudstream/cloudstream/releases/tag/pre-release) APK'sini indirip kurun
2. **Ayarlar > Eklentiler > Depo Ekle** bolumune gidin
3. Kisa kod olarak `baristomruk-max` yazin veya asagidaki URL'yi yapistirin:

```
https://raw.githubusercontent.com/baristomruk-max/BTVault/master/repo.json
```

---

## Icerik

<table>
  <tr>
    <th>Platform</th>
    <th>Tur</th>
    <th>Durum</th>
  </tr>
  <tr>
    <td>🎬 <strong>FilmMakinesi</strong></td>
    <td>Film</td>
    <td>✅ Aktif</td>
  </tr>
  <tr>
    <td>🎬 <strong>HDFilmCehennemi</strong></td>
    <td>Film & Dizi</td>
    <td>✅ Aktif</td>
  </tr>
  <tr>
    <td>🎬 <strong>KultFilmler</strong></td>
    <td>Film & Dizi</td>
    <td>✅ Aktif</td>
  </tr>
  <tr>
    <td>📺 <strong>DiziPal</strong></td>
    <td>Dizi & Film</td>
    <td>✅ Aktif</td>
  </tr>
  <tr>
    <td>📺 <strong>DiziBox</strong></td>
    <td>Yabanci Dizi</td>
    <td>✅ Aktif</td>
  </tr>
  <tr>
    <td>📺 <strong>SezonlukDizi</strong></td>
    <td>Yabanci Dizi</td>
    <td>✅ Aktif</td>
  </tr>
  <tr>
    <td>🎭 <strong>TurkAnime</strong></td>
    <td>Anime</td>
    <td>✅ Aktif</td>
  </tr>
  <tr>
    <td>🎭 <strong>AnimeciX</strong></td>
    <td>Anime</td>
    <td>✅ Aktif</td>
  </tr>
</table>

---

## Ozellikler

- 🔍 **Tek Arama** - Tum platformlarda ayni anda arama
- 📥 **Indirme** - Filmleri ve dizileri cevirime indirin
- 📱 **TV Desteği** - Android TV ve tabletlerde calisir
- 🔄 **Otomatik Guncelleme** - Yeni bolumler otomatik eklenir
- 🎬 **Kiralama Modu** - Chromecast ile televizyona aktarin

---

## Yeni Eklenti Ekleme

```bash
# 1. Bu repoyu fork edin
# 2. Yeni klasor olusturun
mkdir YeniPlatform

# 3. Gerekli dosyalari olusturun
# - build.gradle.kts (metadata)
# - src/main/AndroidManifest.xml
# - src/main/kotlin/com/btcozum/btvault/YeniPlatformPlugin.kt
# - src/main/kotlin/com/btcozum/btvault/YeniPlatform.kt

# 4. Push edin, GitHub Actions otomatik derler
git push origin main
```

---

## Gelistirme

```bash
# Yerel derleme
.\gradlew.bat FilmMakinesi:make

# Tek provider derleme
.\gradlew.bat HDFilmCehennemi:make

# Tum provider'lari derleme
.\gradlew.bat make
```

---

## Tesekkurler

| | Proje |
|---|---|
| 🙏 | [KekikAkademi](https://github.com/keyiflerolsun/Kekik-cloudstream) - Kaynak provider kodlari |
| 🙏 | [CloudStream](https://github.com/recloudstream/cloudstream) - Uygulama |
| 🙏 | [recloudstream](https://github.com/recloudstream) - Topluluk |

---

<p align="center">
  <sub>BTVault &copy; 2026 | <a href="https://github.com/baristomruk-max">baristomruk-max</a> | GPL-3.0</sub>
</p>
