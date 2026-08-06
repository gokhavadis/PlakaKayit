# Plaka Kayıt V3 Beta

Android üzerinde çalışan, izinli alanlarda plaka ve araç kaydı tutmaya yönelik prototip.

## V3 özellikleri

- CameraX ile canlı kamera
- ML Kit OCR ile Türk plakası okuma
- MediaPipe + EfficientDet Lite0 ile cihaz üzerinde araç türü tanıma
  - Otomobil
  - Kamyon
  - Otobüs
  - Motosiklet
- Araç bölgesinden renk tahmini
- Plaka ve araç bilgilerinin Android Keystore anahtarıyla şifrelenmesi
- Marka, model ve renk bilgilerini elle düzenleme
- Arama, tek kayıt silme, tümünü silme ve CSV dışa aktarma
- Sistem, açık, koyu ve AMOLED temaları
- GitHub Releases tabanlı güncelleme ekranı

## Yapay zekâ modeli

APK iş akışı, resmi Google/TensorFlow EfficientDet Lite0 modelini derleme sırasında indirir:

`app/src/main/assets/efficientdet-lite0.tflite`

Yerel Android Studio derlemesi yapacaksanız modeli aynı konuma indirmeniz gerekir.

## Sınırlar

V3 Beta otomatik olarak araç türünü ve rengi kaydeder. Marka/model alanları henüz özel bir marka-model veri kümesiyle eğitilmiş model içermediği için elle düzenlenir. Düşük güvenli tahminlerde ayarlardaki minimum güven oranı artırılabilir.

Bu uygulama yalnızca sahibinin veya yetkilinin izin verdiği otopark, filo ve özel alanlarda kullanılmalıdır.
