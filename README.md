# Plaka Kayıt V2

Android telefonda CameraX ve ML Kit OCR kullanarak Türk plakalarını algılayan, kayıtları cihazda AES-GCM ile şifreli saklayan prototip.

## V2 özellikleri

- Kamera görüntüsünden Türk plakası okuma
- Aynı plakada son görülme ve görülme sayısını güncelleme
- Marka, model ve renk bilgisini ekleme/düzenleme
- Plaka, marka, model veya renge göre arama
- Tek kaydı silme veya tüm kayıtları temizleme
- Kayıtları CSV dosyasına aktarma
- Plaka ve araç bilgilerini Android Keystore anahtarıyla şifreli saklama
- Görüntü ve konum kaydetmeme

## APK üretme

GitHub Actions, `main` dalına her gönderimde debug APK üretir.

1. GitHub'da **Actions** sekmesini açın.
2. En son **Android APK** çalışmasına girin.
3. Yeşil tikten sonra **Artifacts** bölümündeki `PlakaKayit-debug-apk` paketini indirin.
4. ZIP içindeki `app-debug.apk` dosyasını Android telefona kurun.

## Sonraki aşama

Kamera görüntüsünden marka, model ve renk tahmini için ayrıca optimize edilmiş bir araç sınıflandırma modeli gerekir. V2'de bu alanlar kullanıcı tarafından düzenlenebilir ve şifreli tutulur.
