# BTVault - CloudStream Turkce Eklenti Deposu

Turkce film ve dizi platformlari icin CloudStream eklentileri.

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

| Platform    | Tur           | Durum |
|-------------|---------------|-------|
| FilmMakinesi| Film          | Aktif |
| DiziPal     | Dizi & Film   | Aktif |

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

## Lisans

GPL-3.0
