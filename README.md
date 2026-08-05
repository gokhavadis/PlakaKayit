# Plaka Kayıt MVP

Android telefonda CameraX + ML Kit OCR kullanarak Türk plaka formatlarını algılayan ve kayıtları cihazda AES-GCM ile şifreli saklayan ilk prototip.

## Bu sürüm ne yapar?

- Arka kamerayı açar.
- OCR ile görüntüdeki metin satırlarını okur.
- 01–81 il koduna uyan yaygın Türk plaka formatlarını ayıklar.
- Aynı plakayı 20 saniye içinde yeniden kaydetmez.
- Plaka metnini Android Keystore anahtarıyla AES-GCM şifreler.
- İlk görülme, son görülme ve görülme sayısını yerel SQLite veritabanında tutar.
- Görüntü, ses ve konum kaydetmez.

## Çalıştırma

1. Android Studio'da bu klasörü açın.
2. SDK Manager'dan Android SDK 36 ve JDK 17'nin hazır olduğundan emin olun.
3. Android Studio, projeyi ilk açılışta kendi Gradle kurulumu ile senkronize etsin. Gerekirse Settings > Build Tools > Gradle bölümünde 'Gradle from specified location' veya Android Studio'nun önerdiği sürümü seçin.
4. Android 8.0+ gerçek telefonda çalıştırın.
5. Kamera iznini verin ve plakayı kadraja büyük, net ve yatay biçimde alın.

## Sınırlar

Bu, OCR tabanlı ilk MVP'dir. Ayrı bir plaka konumlandırma modeli henüz yoktur; uzaktaki, eğik, bulanık veya gece çekilen plakaları kaçırabilir. Araç marka/model algılama ikinci aşamadır.

## Gizlilik ve kullanım

Yalnızca izinli özel alanlarda, kendi otoparkınızda veya test ortamında kullanın. Kamusal alanda sistematik araç takibi için hukuki dayanak, aydınlatma ve veri saklama politikası gerekebilir.

## APK üretme

Android Studio içinde projeyi açıp **Build > Build APK(s)** seçeneğini kullanabilirsiniz.

GitHub üzerinden otomatik derleme için proje kökünde `.github/workflows/build-apk.yml` bulunur. Proje GitHub'a yüklendiğinde **Actions > Android APK > Run workflow** ile APK oluşturulur. Çıktı `PlakaKayit-debug-apk` adıyla indirilir.
