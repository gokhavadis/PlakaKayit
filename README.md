# Plaka Kayıt V3.2 Beta

Yetkili özel alanlar, otoparklar ve filolar için Android plaka, araç ve güvenlik olay analizi prototipi.

## V3.2 yenilikleri

- Ayrı **Güvenlik** ekranı ve canlı kamera analizi
- Kişi sayısı ve sahne içinde geçici `Kişi-1`, `Kişi-2` takibi
- Üst ve alt kıyafet rengi tahmini
- Sırt çantası, el çantası ve valiz algılama
- Ekran içinde hareket yönü ve hareket hızı
- Hızlı hareket olayı
- Ayarlanabilir uzun bekleme olayı
- Kısıtlı bölgeye giriş olayı
- Yüzün görünürlük ve görüntü kalite tahmini; kimlik tanıma yapılmaz
- Yakın zamanda görülen plaka ile olay kaydını ilişkilendirme
- Şifreli olay kayıtları
- SHA-256 özeti içeren JSON olay paketi dışa aktarma

## V3.1 özellikleri

- Sadece kayıt / Giriş / Çıkış modu
- İçeride bulunan araç sayısı
- Araç profil ekranı ve giriş-çıkış geçmişi
- PIN ve biyometrik uygulama kilidi
- Sistem, açık, koyu ve AMOLED temaları
- Araç türü ve renk tahmini
- CSV raporu ve güncelleme merkezi

## Sınırlar

Bu beta sürüm görüntü veya video kaydetmez. Kimlik, isim, yaş, cinsiyet, etnik köken, duygu veya suçluluk tahmini yapmaz. Hızlı hareket ve uzun bekleme gibi sonuçlar yapay zekâ tahminidir ve insan doğrulaması gerektirir. Şapka, kask, yüz maskesi, düşme ve kavga analizi bu sürümde güvenilir biçimde desteklenmez.

## Gizlilik

Plaka, araç profili ve güvenlik olayı ayrıntıları Android Keystore anahtarıyla cihazda şifrelenir. Uygulama yalnızca sahibinin veya yetkilinin izin verdiği özel alanlarda kullanılmalıdır.

## Derleme

GitHub Actions, EfficientDet Lite0 modelini resmi TensorFlow depolama adresinden indirir ve debug APK üretir. Artefakt adı:

`PlakaKayit-V32-debug-apk`
