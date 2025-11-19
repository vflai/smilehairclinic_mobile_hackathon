package com.google.mediapipe.examples.facelandmarker

/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max
import kotlin.math.min
import kotlin.math.atan2
import kotlin.math.sqrt

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: FaceLandmarkerResult? = null
    private var linePaint = Paint()
    private var pointPaint = Paint()

    // --- YENİ ÇİZİM NESNELERİ ---
    private var poseTextPaint = Paint() // Üst orta poz metni
    private var poseBgPaint = Paint()   // Üst orta poz arka planı
    private var instructionPaint = Paint() // Alt talimat metni
    private var ellipsePaint = Paint()  // Kılavuz elips
    private var ellipsePaintSuccess = Paint() // Elips (başarı durumu)
    private var eyebrowPaint = Paint() // YENİ: Kaş boyası
    // ---

    // --- Durum Değişkenleri ---
    private var pitch: Float = 0f
    private var yaw: Float = 0f
    private var roll: Float = 0f
    private var hasPose: Boolean = false
    private var showCaptureMessage: Boolean = false

    // --- YENİ Durum Değişkenleri ---
    private var guideEllipse: RectF? = null
    private var instructionText: String = "Yüzünü Ortala"
    private var ellipseSuccess: Boolean = false // Elipsin içindeyse yeşil yap
    // ---

    private var captureMessagePaint = Paint()
    private var captureBgPaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    init {
        initPaints()
    }

    fun clear() {
        results = null
        linePaint.reset()
        pointPaint.reset()
        poseTextPaint.reset()
        poseBgPaint.reset()
        instructionPaint.reset()
        ellipsePaint.reset()
        ellipsePaintSuccess.reset()
        captureMessagePaint.reset()
        captureBgPaint.reset()
        eyebrowPaint.reset() // YENİ

        hasPose = false
        showCaptureMessage = false
        instructionText = "Yüzünü Ortala"
        ellipseSuccess = false

        invalidate()
        initPaints()
    }

    private fun initPaints() {
        linePaint.color =
            ContextCompat.getColor(context!!, R.color.mp_color_primary)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE

        pointPaint.color = Color.YELLOW
        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style = Paint.Style.FILL

        // --- YENİ/GÜNCELLENMİŞ STILLER ---
        // Üst-Orta Baş Pozu Metni
        poseTextPaint.color = Color.WHITE
        poseTextPaint.textSize = 36f
        poseTextPaint.style = Paint.Style.FILL
        poseTextPaint.textAlign = Paint.Align.CENTER // YATAYDA ORTALA

        poseBgPaint.color = Color.argb(192, 0, 0, 0)
        poseBgPaint.style = Paint.Style.FILL

        // Kılavuz Elips
        ellipsePaint.color = Color.WHITE
        ellipsePaint.strokeWidth = 8f
        ellipsePaint.style = Paint.Style.STROKE
        ellipsePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)

        // Elips Başarı Rengi
        ellipsePaintSuccess.color = Color.GREEN
        ellipsePaintSuccess.strokeWidth = 10f
        ellipsePaintSuccess.style = Paint.Style.STROKE

        // Alt Talimat Metni
        instructionPaint.color = Color.WHITE
        instructionPaint.textSize = 60f
        instructionPaint.isFakeBoldText = true
        instructionPaint.style = Paint.Style.FILL
        instructionPaint.textAlign = Paint.Align.CENTER

        // YENİ: Kaş boyası stili
        eyebrowPaint.color = Color.RED
        eyebrowPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        eyebrowPaint.style = Paint.Style.STROKE
        // ---

        // Fotoğraf Çekildi Mesajı
        captureMessagePaint.color = Color.WHITE
        captureMessagePaint.textSize = 72f
        captureMessagePaint.isFakeBoldText = true
        captureMessagePaint.textAlign = Paint.Align.CENTER

        captureBgPaint.color = Color.argb(200, 46, 204, 113)
        captureBgPaint.style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Yüz landmarkları yoksa her şeyi temizle
        if (results?.faceLandmarks().isNullOrEmpty()) {
            showCaptureMessage = false
            clear()
            return
        }

        // --- YENİ ÇİZİM SIRALAMASI ---
        results?.let { faceLandmarkerResult ->
            // 1. Landmarkları ve bağlantıları çiz
            val scaledImageWidth = imageWidth * scaleFactor
            val scaledImageHeight = imageHeight * scaleFactor
            val offsetX = (width - scaledImageWidth) / 2f
            val offsetY = (height - scaledImageHeight) / 2f

            faceLandmarkerResult.faceLandmarks().forEach { faceLandmarks ->
                drawFaceLandmarks(canvas, faceLandmarks, offsetX, offsetY)
                drawFaceConnectors(canvas, faceLandmarks, offsetX, offsetY)
                // YENİ: Kaşları çiz
                drawEyebrowConnectors(canvas, faceLandmarks, offsetX, offsetY)
            }

            // 2. Kılavuz Elipsi Çiz
            drawGuide(canvas)

            // 3. Baş Pozu Metnini Çiz (Üst-Orta)
            drawPoseText(canvas)

            // 4. Fotoğraf Çekildi Mesajını Çiz (Gerekiyorsa)
            if (showCaptureMessage) {
                drawCaptureMessage(canvas)
            }
            // --- ÇİZİM SIRALAMASI SONU ---
        }
    }

    // YENİ: Baş Pozu (Yaw/Pitch/Roll) metnini Üst-Ortaya çizer
    private fun drawPoseText(canvas: Canvas) {
        if (!hasPose) return

        val pitchText = "Pitch: ${pitch.toInt()}°"
        val yawText = "Yaw: ${yaw.toInt()}°"
        val rollText = "Roll: ${roll.toInt()}°"
        val textLines = listOf(yawText, pitchText, rollText)

        val maxTextWidth = textLines.maxOf { poseTextPaint.measureText(it) }
        val padding = 20f
        val bgWidth = maxTextWidth + (padding * 2)
        val bgHeight = (poseTextPaint.textSize * textLines.size) + (padding * 2)

        // Yatayda tam ortala
        val centerX = width / 2f
        val bgLeft = centerX - (bgWidth / 2)
        val bgTop = 20f // Üstten boşluk

        val bgRect = RectF(bgLeft, bgTop, bgLeft + bgWidth, bgTop + bgHeight)
        canvas.drawRect(bgRect, poseBgPaint)

        var yPos = bgTop + padding + poseTextPaint.textSize

        textLines.forEach {
            // 'centerX' kullanılarak metin yatayda ortalanır
            canvas.drawText(it, centerX, yPos, poseTextPaint)
            yPos += poseTextPaint.textSize
        }
    }

    // YENİ: Kılavuz Elipsi ve Alt Talimat Metnini çizer
    private fun drawGuide(canvas: Canvas) {
        guideEllipse?.let { rect ->
            // Koşul sağlandıysa elipsi yeşil çiz, değilse beyaz
            val currentEllipsePaint = if(ellipseSuccess) ellipsePaintSuccess else ellipsePaint

            // --- YENİ: Pulsing (Nefes Alma) Animasyonu ---
            // Zaman bazlı bir ölçek faktörü hesapla (örn. 1.0 ile 1.05 arasında gidip gelir)
            val time = System.currentTimeMillis()
            val scale = 1f + 0.02f * kotlin.math.sin(time / 300.0).toFloat() // Hız: 300ms, Genlik: %2

            // Elipsin merkezini koruyarak ölçekle
            val cx = rect.centerX()
            val cy = rect.centerY()
            
            canvas.save()
            canvas.scale(scale, scale, cx, cy)
            canvas.drawOval(rect, currentEllipsePaint)
            canvas.restore()
            
            // Animasyonun devam etmesi için sürekli çizim iste
            if (!ellipseSuccess) { // Sadece başarı sağlanmadığında (aktifken) animasyon yap
                postInvalidateOnAnimation()
            }
            // ---------------------------------------------
        }

        // Talimat metnini elipsin altına çiz
        val yPos = guideEllipse?.bottom ?: (height * 0.8f)
        // YENİ: Metin her zaman BOLD (initPaints içinde ayarlandı ama burada da emin olalım)
        instructionPaint.isFakeBoldText = true 
        canvas.drawText(instructionText, width / 2f, yPos + 80f, instructionPaint)
    }

    // DÜZELTİLDİ: Mesajı ve arka planını ekranın tam ortasına çizer
    private fun drawCaptureMessage(canvas: Canvas) {
        val message = "FOTOĞRAF ÇEKİLDİ!"
        val padding = 50f

        // Metin ölçülerini al
        val textWidth = captureMessagePaint.measureText(message)
        val textMetrics = captureMessagePaint.fontMetrics
        val textHeight = textMetrics.descent - textMetrics.ascent // Gerçek metin yüksekliği

        // Arka plan boyutları
        val bgWidth = textWidth + 2 * padding
        val bgHeight = textHeight + 2 * padding

        // Ekran merkezi
        val centerX = width / 2f
        val centerY = height / 2f

        // Arka plan koordinatları
        val bgLeft = centerX - bgWidth / 2f
        val bgTop = centerY - bgHeight / 2f
        val bgRight = centerX + bgWidth / 2f
        val bgBottom = centerY + bgHeight / 2f

        val bgRect = RectF(bgLeft, bgTop, bgRight, bgBottom)
        canvas.drawRoundRect(bgRect, 20f, 20f, captureBgPaint)

        // Metni dikey olarak ortalamak için Y koordinatı
        val textY = centerY - (textMetrics.ascent + textMetrics.descent) / 2

        canvas.drawText(message, centerX, textY, captureMessagePaint)
    }

    private fun drawFaceLandmarks(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        faceLandmarks.forEach { landmark ->
            val x = landmark.x() * imageWidth * scaleFactor + offsetX
            val y = landmark.y() * imageHeight * scaleFactor + offsetY
            canvas.drawPoint(x, y, pointPaint)
        }
    }

    private fun drawFaceConnectors(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        FaceLandmarker.FACE_LANDMARKS_CONNECTORS.filterNotNull().forEach { connector ->
            val startLandmark = faceLandmarks.getOrNull(connector.start())
            val endLandmark = faceLandmarks.getOrNull(connector.end())

            if (startLandmark != null && endLandmark != null) {
                val startX = startLandmark.x() * imageWidth * scaleFactor + offsetX
                val startY = startLandmark.y() * imageHeight * scaleFactor + offsetY
                val endX = endLandmark.x() * imageWidth * scaleFactor + offsetX
                val endY = endLandmark.y() * imageHeight * scaleFactor + offsetY

                canvas.drawLine(startX, startY, endX, endY, linePaint)
            }
        }
    }

    /**
     * YENİ: Kaşların üstünü kırmızı renkte çizer
     */
    private fun drawEyebrowConnectors(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        // Çizgileri çizecek yardımcı bir iç fonksiyon
        val drawLines = { connectors: List<Pair<Int, Int>> ->
            connectors.forEach { connector ->
                val startLandmark = faceLandmarks.getOrNull(connector.first)
                val endLandmark = faceLandmarks.getOrNull(connector.second)

                if (startLandmark != null && endLandmark != null) {
                    val startX = startLandmark.x() * imageWidth * scaleFactor + offsetX
                    val startY = startLandmark.y() * imageHeight * scaleFactor + offsetY
                    val endX = endLandmark.x() * imageWidth * scaleFactor + offsetX
                    val endY = endLandmark.y() * imageHeight * scaleFactor + offsetY

                    // Kırmızı kaş boyasını kullan
                    canvas.drawLine(startX, startY, endX, endY, eyebrowPaint)
                }
            }
        }

        // İki kaşı da çiz
        drawLines(LEFT_EYEBROW_UPPER_CONNECTORS)
        drawLines(RIGHT_EYEBROW_UPPER_CONNECTORS)
    }


    private fun rotationMatrixToAngles(matrix: FloatArray): Triple<Float, Float, Float> {
        if (matrix.size != 16) {
            return Triple(0f, 0f, 0f)
        }
        val m = matrix
        val pitchRad = atan2(m[9].toDouble(), m[10].toDouble())
        val yawRad = atan2(-m[8].toDouble(), sqrt(m[0].toDouble() * m[0].toDouble() + m[4].toDouble() * m[4].toDouble()))
        val rollRad = atan2(m[4].toDouble(), m[0].toDouble())
        val pitch = Math.toDegrees(pitchRad).toFloat()
        val yaw = Math.toDegrees(yawRad).toFloat()
        val roll = Math.toDegrees(rollRad).toFloat()
        return Triple(pitch, yaw, roll)
    }

    fun setResults(
        faceLandmarkerResults: FaceLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = faceLandmarkerResults

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                max(width * 1f / imageWidth, height * 1f / imageHeight)
            }
        }

        val matrixes = faceLandmarkerResults.facialTransformationMatrixes()
        if (matrixes.isPresent && matrixes.get().isNotEmpty()) {
            val transformMatrix = matrixes.get()[0]
            val (pitch, yaw, roll) = rotationMatrixToAngles(transformMatrix)
            this.pitch = pitch
            this.yaw = yaw
            this.roll = roll
            this.hasPose = true
        } else {
            this.hasPose = false
        }

        invalidate()
    }

    // YENİ: Kılavuz Elipsi ayarlar (CameraFragment'tan çağrılır)
    fun setGuideEllipse(rect: RectF) {
        this.guideEllipse = rect
        invalidate()
    }

    // YENİ: Talimat metnini ayarlar (CameraFragment'tan çağrılır)
    fun setInstruction(text: String, success: Boolean = false) {
        this.instructionText = text
        this.ellipseSuccess = success // Elips rengini güncelle
        invalidate()
    }

    // Fotoğraf çekildi mesajını gösterir
    fun showCaptureSuccess() {
        showCaptureMessage = true
        invalidate()
        postDelayed({
            showCaptureMessage = false
            invalidate()
        }, 1500)
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 8F
        private const val TAG = "Face Landmarker Overlay"

        // YENİ: Kaş üstü için bağlantı listeleri
        // leftEyebrowUpper: [383, 300, 293, 334, 296, 336, 285, 417]
        private val LEFT_EYEBROW_UPPER_CONNECTORS = listOf(
            Pair(383, 300),
            Pair(300, 293),
            Pair(293, 334),
            Pair(334, 296),
            Pair(296, 336),
            Pair(336, 285),
            Pair(285, 417)
        )

        // rightEyebrowUpper: [156, 70, 63, 105, 66, 107, 55, 193]
        private val RIGHT_EYEBROW_UPPER_CONNECTORS = listOf(
            Pair(156, 70),
            Pair(70, 63),
            Pair(63, 105),
            Pair(105, 66),
            Pair(66, 107),
            Pair(107, 55),
            Pair(55, 193)
        )
    }
}