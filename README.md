# BTVault - CloudStream Turkce Eklenti Deposu

Turkce film, dizi ve anime platformlari icin CloudStream eklentileri.

## Kurulum

1. [CloudStream Pre-Release](https://github.com/recloudstream/cloudstream/releases/tag/pre-release) APK'sini indirip kurun.
2. Uygulamada **Ayarlar > Eklentiler > Depo Ekle** bolumune gidin.
3. Asagidaki bilgileri girin:
   - **Depo Adi:** `BTVault` (bos birakilabilir)
   - **Depo URL:** `BTcozum` (kisakod) veya tam URL:
     ```
     https://raw.githubusercontent.com/BTcozum/BTVault/master/repo.json
     ```

## Icerik

### Filmler
| Platform        | Tur                | Durum |
|-----------------|--------------------|-------|
| FilmMakinesi    | Film               | Aktif |
| HDFilmCehennemi | Film & Dizi        | Aktif |
| KultFilmler     | Film & Dizi        | Aktif |

### Diziler
| Platform        | Tur                | Durum |
|-----------------|--------------------|-------|
| DiziPal         | Dizi & Film        | Aktif |
| DiziBox         | Yabanci Dizi       | Aktif |
| SezonlukDizi    | Yabanci Dizi       | Aktif |

### Animeler
| Platform        | Tur                | Durum |
|-----------------|--------------------|-------|
| TurkAnime       | Anime              | Aktif |
| AnimeciX        | Anime              | Aktif |

## Eklenti Ekleme

Yeni Turkce platform eklemek icin:

1. Bu repoyu fork edin
2. Yeni klasor olusturun (ornegin `YeniPlatform/`)
3. `build.gradle.kts` dosyasini olusturun
4. Plugin ve Provider siniflarini yazin
5. `settings.gradle.kts` otomatik olarak yeni klasoru sececektir
6. Push edin ve GitHub Actions'in derlemesini bekleyin

## Gelistirme

```bash
# Windows
.\gradlew.bat FilmMakinesi:make

# Linux/Mac
./gradlew FilmMakinesi:make
```

## Tesekkurler

- [KekikAkademi](https://github.com/keyiflerolsun/Kekik-cloudstream) - Kaynak provider kodlari
- [CloudStream](https://github.com/recloudstream/cloudstream) - Uygulama

## Lisans

GPL-3.0
