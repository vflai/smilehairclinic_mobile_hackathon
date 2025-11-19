import cv2
import mediapipe as mp
import numpy as np
import os
import glob
import matplotlib.pyplot as plt
import seaborn as sns

# --- AYARLAR ---
ROOT_DIR = r"C:\Users\PANLI\Desktop\fotoğraf\dataset" 
ALIGN_SIZE = (512, 512)
IMG_EXTENSIONS = {'jpg', 'jpeg', 'png'}

# Görselleştirme Ayarları (Dark Theme)
plt.style.use('dark_background')

# MediaPipe Kurulumu
mp_selfie_segmentation = mp.solutions.selfie_segmentation
selfie_segmentation = mp_selfie_segmentation.SelfieSegmentation(model_selection=1)

def cv2_imread_tr(path):
    try:
        with open(path, "rb") as f:
            bytes_data = bytearray(f.read())
            numpy_array = np.asarray(bytes_data, dtype=np.uint8)
            image = cv2.imdecode(numpy_array, cv2.IMREAD_COLOR)
            return image
    except Exception:
        return None

def get_segmentation_mask(image):
    if image is None: return None
    image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    results = selfie_segmentation.process(image_rgb)
    mask = results.segmentation_mask
    if mask is None: return None
    binary_mask = (mask > 0.5).astype(np.uint8)
    return binary_mask

def align_mask(binary_mask):
    points = cv2.findNonZero(binary_mask)
    if points is None:
        return np.zeros(ALIGN_SIZE, dtype=np.uint8)
    x, y, w, h = cv2.boundingRect(points)
    cropped_mask = binary_mask[y:y+h, x:x+w]
    aligned_mask = cv2.resize(cropped_mask, ALIGN_SIZE, interpolation=cv2.INTER_NEAREST)
    return aligned_mask

def calculate_iou(mask1, mask2):
    intersection = np.logical_and(mask1, mask2)
    union = np.logical_or(mask1, mask2)
    iou_score = np.sum(intersection) / np.sum(union) if np.sum(union) > 0 else 0.0
    return iou_score

def plot_heatmap(matrix, class_name, avg_score):
    """
    10x10'luk IoU Matrisini çizer.
    """
    plt.figure(figsize=(10, 8))
    
    # Renk haritası: 'magma' veya 'plasma' dark theme'de güzel durur
    sns.heatmap(matrix, annot=True, fmt=".2f", cmap="magma", vmin=0, vmax=1,
                xticklabels=range(1, len(matrix)+1),
                yticklabels=range(1, len(matrix)+1))
    
    plt.title(f"{class_name} - Tutarlılık Matrisi (Ort: {avg_score:.3f})", color='white', fontsize=14)
    plt.xlabel("Fotoğraf ID", color='gray')
    plt.ylabel("Fotoğraf ID", color='gray')
    
    # Pencereyi göster (Kod burada durur, pencereyi kapatınca devam eder)
    plt.tight_layout()
    plt.show()

def plot_summary(results):
    """
    Tüm sınıfların ortalamasını kıyaslayan Bar Chart.
    """
    classes = list(results.keys())
    scores = list(results.values())

    plt.figure(figsize=(10, 6))
    colors = sns.color_palette("viridis", len(classes))
    
    bars = plt.bar(classes, scores, color=colors)
    
    plt.title("Tüm Açılar İçin Ortalama Tutarlılık (IoU) Başarısı", fontsize=16, color='white')
    plt.ylabel("Ortalama IoU Skoru", color='white')
    plt.ylim(0, 1.1) # 0 ile 1.1 arası (etiket sığsın diye)
    
    # Çubukların üzerine değerleri yaz
    for bar in bars:
        yval = bar.get_height()
        plt.text(bar.get_x() + bar.get_width()/2, yval + 0.02, f"{yval:.3f}", 
                 ha='center', color='white', fontweight='bold')

    plt.grid(axis='y', linestyle='--', alpha=0.3)
    plt.show()

def process_folders(root_path):
    if not os.path.exists(root_path):
        print(f"HATA: '{root_path}' yolu bulunamadı!")
        return

    subfolders = [f.path for f in os.scandir(root_path) if f.is_dir()]
    print(f"--- Hedef: {root_path} | Alt Klasörler: {len(subfolders)} ---\n")

    # Sonuçları saklamak için sözlük
    final_results = {}

    for folder in subfolders:
        folder_name = os.path.basename(folder)
        image_files = set()
        for ext in IMG_EXTENSIONS:
            image_files.update(glob.glob(os.path.join(folder, f"*.{ext}")))
            image_files.update(glob.glob(os.path.join(folder, f"*.{ext.upper()}")))
        
        image_files = sorted(list(image_files))
        
        if len(image_files) < 2:
            print(f"[{folder_name}] Yetersiz fotoğraf ({len(image_files)}), geçiliyor.")
            continue

        print(f"[{folder_name}] {len(image_files)} fotoğraf işleniyor ve matris oluşturuluyor...")
        
        # 1. Maskeleri Hazırla
        aligned_masks = []
        for img_path in image_files:
            img = cv2_imread_tr(img_path)
            if img is None: continue
            
            raw_mask = get_segmentation_mask(img)
            if raw_mask is not None:
                aligned = align_mask(raw_mask)
                aligned_masks.append(aligned)
        
        count = len(aligned_masks)
        if count < 2: continue

        # 2. Matris Oluştur (NxN)
        iou_matrix = np.zeros((count, count))
        
        for i in range(count):
            for j in range(count):
                score = calculate_iou(aligned_masks[i], aligned_masks[j])
                iou_matrix[i, j] = score
        
        # Ortalamayı hesapla (Matrisin ortalaması)
        avg_iou = np.mean(iou_matrix)
        final_results[folder_name] = avg_iou
        
        print(f"   -> {folder_name} Tamamlandı. Ortalama: {avg_iou:.4f}")
        
        # --- GÖRSELLEŞTİRME 1: MATRİS ---
        plot_heatmap(iou_matrix, folder_name, avg_iou)

    # --- GÖRSELLEŞTİRME 2: ÖZET GRAFİK ---
    if final_results:
        print("\n--- TÜM İŞLEMLER BİTTİ: Özet Grafik Çiziliyor ---")
        plot_summary(final_results)
    else:
        print("Gösterilecek sonuç bulunamadı.")

if __name__ == "__main__":
    process_folders(ROOT_DIR)
