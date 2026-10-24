# TCL Live+ (root gerektirmez)

TCL Android TV'lerde canlı TV deneyimini geliştiren, root gerektirmeyen bir katman.
TCL'in kanal listesini değiştirmez; erişilebilirlik servisi + overlay ile üzerine biner.
Kumandadan OK'a basınca TCL'in listesi yerine bu katman açılır.

> TCL ile resmî bağlantılı değildir. TCL adı, kanal adları ve logolar sahiplerine aittir.

## Ekran görüntüleri

| Kanal listesi | IPTV listesi |
|---|---|
| ![Kanal listesi](docs/screenshots/kanal-listesi.png) | ![IPTV listesi](docs/screenshots/iptv-listesi.png) |

| TKGS | Ayarlar |
|---|---|
| ![TKGS](docs/screenshots/tkgs.png) | ![Ayarlar](docs/screenshots/ayarlar.png) |

## Kolay Kurulum (Windows) — önerilen

Bilgisayar bilgisi gerekmez. ADB'yi ayrıca indirmeniz veya komut yazmanız gerekmez.
`Kur.bat`, ADB'yi Google'ın resmî platform-tools paketinden otomatik indirir.

### 1. TV'de geliştirici seçeneklerini açın

1. **Ayarlar → Sistem → Hakkında** bölümüne gidin.
2. **Yapı numarası** üzerine 7 kez basın.
3. **Ayarlar → Sistem → Geliştirici seçenekleri** bölümüne girin.
4. **USB hata ayıklama** veya **ADB hata ayıklama** seçeneğini açın.

### 2. TV'nin IP adresini alın

1. **Ayarlar → Ağ → Durum** bölümüne gidin.
2. TV'nin IP adresini not edin (örnek: `192.168.1.50`).
3. TV ve bilgisayar aynı Wi-Fi ağında olmalı.

### 3. Kurulumu başlatın

1. [TCL-Live-Plus-Kurulum.zip](https://github.com/mmzffrgl-web/tcl-live-plus/releases/latest/download/TCL-Live-Plus-Kurulum.zip) dosyasını indirin.
2. ZIP'i bir klasöre çıkarın.
3. `Kur.bat` dosyasına çift tıklayın.
4. TV'nin IP adresini yazın.
5. TV'de çıkan **izin** penceresini onaylayın.
6. TV'de **Ayarlar → Erişilebilirlik → TCL Live+** servisini açın.
7. Bilgisayarda Enter'a basın; uygulama TV'de açılır.

`Kur.bat` ilk çalıştırmada yaklaşık 8 MB'lık ADB paketini indirir; sonraki çalıştırmalarda tekrar indirmez.
Yönetici izni gerekmez. Ayrıntılı rehber: [`kolay-kurulum/OKUBENI.txt`](kolay-kurulum/OKUBENI.txt).

### Takılırsanız

- **TV bulunamadı:** TV ve bilgisayarın aynı Wi-Fi'da olduğundan, ADB/USB hata ayıklamanın açık olduğundan ve IP'nin doğru olduğundan emin olun.
- **`unauthorized`:** TV ekranındaki izin penceresini onaylayın. Pencere görünmüyorsa hata ayıklamayı kapatıp açın.
- **Windows uyarısı:** “Windows protected your PC” çıkarsa **More info → Run anyway** seçin.

## Manuel Kurulum (ADB zaten kuruluysa)

1. [TCL-Live-Plus.apk](https://github.com/mmzffrgl-web/tcl-live-plus/releases/latest/download/TCL-Live-Plus.apk) indirin.
2. TV'ye yükleyin: `adb install -r TCL-Live-Plus.apk`
3. TV'de **Ayarlar → Erişilebilirlik → TCL Live+** servisini açın.
4. Uygulamayı açıp kanalları içe aktarın veya IPTV M3U adresini girin.

## Kanalları içe aktarma

1. Uygulamada **Kanalları içe aktar** seçeneğine girin.
2. TV'de OK ile TCL'in kanal listesini açın ve birkaç saniye gezin.
3. GERİ'ye basın, ardından **İçe aktarmayı bitir** seçeneğini kullanın.

Kanal numaraları TCL'in kendi numaralarıdır.

## Özellikler

- OK ile açılan yarı saydam kanal listesi; Türkçe kanal logoları APK içinde
- EPG internetten (XMLTV; varsayılan `epgshare01 TR1`)
- TKGS kanal sırası desteği
- IPTV: otomatik M3U indirme veya Ayarlar'dan özel M3U adresi
- IPTV oynatıcı: HLS/MP4; UP/DOWN ile önceki/sonraki kanal (döngülü); OK ile listeye dönüş; GERİ ile canlı TV
- IPTV listesinde UP/DOWN gezinme, LEFT/RIGHT sayfa, YEŞİL ekran modu
- YouTube tuşuyla YouTube TV
- Ayarlar: EPG adresi, tuner modu (`dvbs`/`dvbt`/`dvbc`), OK yakalama, IPTV M3U adresi

## Kumanda

| Yer | Tuş | İşlev |
|---|---|---|
| Kanal listesi | OK / UP-DOWN / LEFT-RIGHT | Seç / gezin / sayfa |
| Kanal listesi | OK (basılı) / YEŞİL / KIRMIZI | EPG / TKGS / IPTV |
| Kanal listesi | GERİ | Kapat |
| IPTV listesi | OK / UP-DOWN / LEFT-RIGHT | Oynat / gezin / sayfa |
| IPTV listesi | KIRMIZI / GERİ | Kanal listesi / canlı TV |
| IPTV oynatıcı | UP-DOWN / OK / GERİ / YEŞİL | Önceki-sonraki / liste / canlı TV / ekran modu |
| EPG / TKGS | OK / GERİ | Seç / geri |
| Her yerde | YouTube | YouTube TV |

## Geliştirici

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

Gereksinimler: JDK 17+, Android SDK build-tools 34+, platform android-34. Gradle yok; çıktı `TCL-Live-Plus.apk`.

## Notlar

- Root yoktur; TCL tuner'ı veya sistem uygulamaları değiştirilmez.
- Erişilebilirlik servisi kapatılırsa TCL'in orijinal davranışına dönülür.
- TCL firmware güncellemesi kanal değiştirmeyi bozabilir.
- IPTV oynatma, kaynağa ve cihazın MediaPlayer codec desteğine bağlıdır.

## Yasal not

Bu uygulama yayın içermez. IPTV M3U adresini kullanıcı sağlar; varsayılan liste üçüncü taraf bir GitHub deposundan indirilir.
Yalnızca yayın haklarına sahip olduğunuz veya yasal olarak erişim yetkiniz olan içerikleri kullanın.
