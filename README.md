# Plaka Kayıt V3.1 Beta

Yetkili özel alanlar, otoparklar ve filolar için Android plaka ve araç kayıt prototipi.

## V3.1 yenilikleri

- Kamera ekranında **Sadece kayıt / Giriş / Çıkış** modu
- İçeride bulunan araç sayısı
- Tekrarlanan giriş ve çıkışların engellenmesi
- Her plaka için araç profil ekranı
- Marka, model, renk, kategori ve şifreli not alanı
- Giriş–çıkış hareket geçmişi
- Manuel giriş ve çıkış düzeltmesi
- PIN uygulama kilidi
- Desteklenen cihazlarda biyometrik kilit
- 30 saniye arka planda kaldıktan sonra yeniden kilitleme
- Sistem, açık, koyu ve AMOLED temaları
- Otomatik güncelleme kontrolü
- AI araç türü ve renk tahmini
- Genişletilmiş CSV raporu

## Gizlilik

Görüntü ve konum kaydedilmez. Plaka, araç profili, kategori ve not alanları Android Keystore anahtarıyla cihazda şifrelenir. Giriş–çıkış olayları plakanın geri döndürülemez özetiyle ilişkilendirilir.

Uygulama yalnızca sahibinin veya yetkilinin izin verdiği otopark, filo ve özel alanlarda kullanılmalıdır.

## Derleme

GitHub Actions, EfficientDet Lite0 modelini resmi TensorFlow depolama adresinden indirir ve debug APK üretir. Artefakt adı:

`PlakaKayit-V31-debug-apk`
