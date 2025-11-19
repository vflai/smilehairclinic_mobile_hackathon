Self-Capture Tool: Otonom Saç Analizi ve Fotoğraf Asistanı

Mobile Hackathon Final Projesi Smile Hair Clinic & Coderspace iş birliği ile geliştirilmiştir.

Bu proje, saç ekimi veya analizi süreçlerinde kullanıcıların kendi fotoğraflarını (selfie) belirlenen 5 kritik açıdan, yardımsız, tutarlı ve yüksek doğrulukla çekebilmelerini sağlayan akıllı bir Android mobil uygulamasıdır.

Proje Amacı ve Problem Tanımı

Kullanıcıların saç ve kafa derisi bölgelerini kapsayan fotoğrafları dışarıdan yardım almadan çekmeleri genellikle zorlu bir süreçtir. Mevcut yöntemlerdeki tutarsız pozlama ve açı hatalarını gidermek amacıyla bu proje geliştirilmiştir.

Temel Hedef: Kullanıcının yardım almadan, belirlenen açılarda ve tutarlı pozlamalarla fotoğraf çekebilmesi için akıllı, tam otomatik ve yönlendirici bir arayüz (Self-Capture Tool) sunmaktır.

Teknik Mimari ve Yaklaşım

Proje, yüksek maliyet, ağ gecikmesi (latency) ve API bağımlılığı yaratan bulut tabanlı çözümler yerine, işlem gücünü mobil cihazın kendisine yıkan Edge AI (Uçta Yapay Zeka) prensibiyle geliştirilmiştir.

Platform: Android (Native) - Kotlin

Test Cihazı: Xiaomi 14 Ultra (Android 15)

Temel Mimari: Hibrit Model (MediaPipe + YOLO)

Kullanılan Modeller ve Algoritmalar

MediaPipe Face Mesh:

Yüzün takibi ve açısal hesaplamalar için Google’ın hafif ve performanslı modeli kullanılmıştır.

468 adet 3B landmark noktası işlenerek kafa pozisyonu modellenmiştir.

YOLOv8n (Nano):

Yüz referansının bulunmadığı 5. Açı (Arka Donör Bölgesi) tespiti için kullanılmıştır.

Kliniğin veri setiyle model özel olarak eğitilmiştir (Custom Object Detection).

Matematiksel Modelleme (Pose Estimation):

PnP (Perspective-n-Point): 2B görüntü noktalarının 3B dünya koordinatlarıyla eşleştirilmesi.

Rodrigues Dönüşümü: Rotasyon vektörünün matrise çevrilmesi.

Euler Açıları: Pitch (x), Yaw (y) ve Roll (z) değerlerinin hesaplanması.

Modellenen 5 Kritik Açı

Sistem, kafa pozisyonunu H(pitch, yaw, roll) vektörleri olarak modeller ve aşağıdaki pozisyonları otomatik algılar:

Tam Yüz Karşıdan: H(0,0,0)

45 Derece Sağa Bakış: H(0,-45,0)

45 Derece Sola Bakış: H(0,45,0)

Tepe Kısmı (Vertex): H(45,0,0) - Vektörel projeksiyon ile tahmin edilir.

Arka Donör Bölgesi: H(180,0,0) - Nesne tespiti ile yakalanır.

Kılavuzlama ve Otonom Çekim Mekanizması

Kullanıcı deneyimini artırmak için görsel, işitsel ve dokunsal (haptic) geri bildirimler sisteme entegre edilmiştir.

Hizalama ve Toleranslar

Açısal Tolerans: İdeal pozisyonlar için deneysel olarak %5 tolerans belirlenmiştir.

Hizalama Toleransı: Yüz şekillerindeki farklılıklar için %10 tolerans eklenmiştir.

Otomatik Deklanşör: Hedef açı ve konum sağlandığında sistem 1 saniye stabilite bekler ve otomatik çekim yapar.

Açı Bazlı Yöntemler

1, 2 ve 3. Açılar: Yüz landmark verileri kullanılarak elips kılavuz çizilir. Burun ucu referans alınarak yüzün merkezde olup olmadığı kontrol edilir.

4. Açı (Vertex): Yüz landmarklarından yola çıkarak kafa tasının görünmeyen üst sınırını kestiren vektörel projeksiyon yöntemi kullanılır. Başın eğim açısının (pitch) 35-40 derece aralığında olması beklenir.

5. Açı (Donör): YOLOv8n modeli ile arka kafa bölgesi tespit edilir ve kullanıcı sesli komutlarla yönlendirilir.

Kullanılan Temel Komutlar:

"Yüzünü Ortala", "Başını Dik Tut", "Biraz Yaklaş", "Başını Eğ", "Düz Dur".

Tutarlılık Analizi ve Sonuçlar

Sistemin başarısı, çekilen görüntülerin MediaPipe Selfie Segmentation modeli ile maskelenmesi ve IoU (Intersection over Union) skorlarının hesaplanmasıyla test edilmiştir.

Her açı için 10'ar adet fotoğraf çekilmiş ve çapraz kıyaslama (10x10 matris) yapılmıştır.

Ağır bir son işleme (heavy post-processing) gerek kalmadan, ham veriler üzerinde yüksek kararlılık (State-of-the-art seviyesi) elde edilmiştir.

Detaylı IoU matrisleri ve doğrulama kodları proje reposunda mevcuttur.

Demo

Projenin çalışma prensibini ve canlı demosunu aşağıdaki bağlantıdan izleyebilirsiniz:
