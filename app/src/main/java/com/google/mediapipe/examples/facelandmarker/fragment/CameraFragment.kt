package com.google.mediapipe.examples.facelandmarker.fragment

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_DRAGGING
import com.google.mediapipe.examples.facelandmarker.FaceLandmarkerHelper
import com.google.mediapipe.examples.facelandmarker.MainViewModel
import com.google.mediapipe.examples.facelandmarker.R
import com.google.mediapipe.examples.facelandmarker.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.min
import kotlin.math.max

class CameraFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Face Landmarker"
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private val faceBlendshapesResultAdapter by lazy {
        FaceBlendshapesResultAdapter()
    }

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    private var imageCapture: ImageCapture? = null
    private var isCaptureInProgress: Boolean = false

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    // --- YENİ: AŞAMALI ÇEKİM VE GEOMETRİ ---
    private var currentStage = 1 // 1: Ortala, 2: Sağ, 3: Sol
    private var guideEllipseRect = RectF()
    // ---

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        backgroundExecutor.execute {
            if (faceLandmarkerHelper.isClose()) {
                faceLandmarkerHelper.setupFaceLandmarker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if(this::faceLandmarkerHelper.isInitialized) {
            viewModel.setMaxFaces(faceLandmarkerHelper.maxNumFaces)
            viewModel.setMinFaceDetectionConfidence(faceLandmarkerHelper.minFaceDetectionConfidence)
            viewModel.setMinFaceTrackingConfidence(faceLandmarkerHelper.minFaceTrackingConfidence)
            viewModel.setMinFacePresenceConfidence(faceLandmarkerHelper.minFacePresenceConfidence)
            viewModel.setDelegate(faceLandmarkerHelper.currentDelegate)
            backgroundExecutor.execute { faceLandmarkerHelper.clearFaceLandmarker() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        backgroundExecutor = Executors.newSingleThreadExecutor()

        fragmentCameraBinding.viewFinder.post {
            // Kamera ve Elips Kılavuzunu ayarla
            setUpCamera()
            defineGuideEllipse() // YENİ
        }

        backgroundExecutor.execute {
            faceLandmarkerHelper = FaceLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minFaceDetectionConfidence = viewModel.currentMinFaceDetectionConfidence,
                minFaceTrackingConfidence = viewModel.currentMinFaceTrackingConfidence,
                minFacePresenceConfidence = viewModel.currentMinFacePresenceConfidence,
                maxNumFaces = viewModel.currentMaxFaces,
                currentDelegate = viewModel.currentDelegate,
                faceLandmarkerHelperListener = this
            )
        }
        initBottomSheetControls()
    }

    // YENİ: OverlayView üzerine elips kılavuzunu tanımlar
    private fun defineGuideEllipse() {
        val overlay = fragmentCameraBinding.overlay
        // Overlay'in boyutlarını bekleyerek güvenli bir şekilde al
        overlay.post {
            val w = overlay.width
            val h = overlay.height

            // DÜZELTME: Elips boyutları (Kafaya yakın, daha geniş)
            // Gerekli en = Ekran genişliğinin %65'i (BÜYÜTÜLDÜ)
            val ellipseW = w * 0.50f
            // Yükseklik = Genişliğin 1.3 katı (kafa oranı)
            val ellipseH = ellipseW * 1.3f

            // Elipsi ortala
            val ellipseL = (w - ellipseW) / 2f

            // DÜZELTME: Elipsi dikeyde tam ortala.
            val ellipseT = (h - ellipseH) / 2f

            val ellipseR = ellipseL + ellipseW
            val ellipseB = ellipseT + ellipseH

            guideEllipseRect = RectF(ellipseL, ellipseT, ellipseR, ellipseB)

            // OverlayView'a elipsi ve ilk talimatı gönder
            overlay.setGuideEllipse(guideEllipseRect)
            overlay.setInstruction("Yüzünü Ortala", false)
        }
    }

    private fun initBottomSheetControls() {
        // ... (Bu fonksiyon değişmedi) ...
        fragmentCameraBinding.bottomSheetLayout.maxFacesValue.text =
            viewModel.currentMaxFaces.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinFaceDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinFaceTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinFacePresenceConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (faceLandmarkerHelper.minFaceDetectionConfidence >= 0.2) {
                faceLandmarkerHelper.minFaceDetectionConfidence -= 0.1f
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (faceLandmarkerHelper.minFaceDetectionConfidence <= 0.8) {
                faceLandmarkerHelper.minFaceDetectionConfidence += 0.1f
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (faceLandmarkerHelper.minFaceTrackingConfidence >= 0.2) {
                faceLandmarkerHelper.minFaceTrackingConfidence -= 0.1f
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (faceLandmarkerHelper.minFaceTrackingConfidence <= 0.8) {
                faceLandmarkerHelper.minFaceTrackingConfidence += 0.1f
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (faceLandmarkerHelper.minFacePresenceConfidence >= 0.2) {
                faceLandmarkerHelper.minFacePresenceConfidence -= 0.1f
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (faceLandmarkerHelper.minFacePresenceConfidence <= 0.8) {
                faceLandmarkerHelper.minFacePresenceConfidence += 0.1f
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.maxFacesMinus.setOnClickListener {
            if (faceLandmarkerHelper.maxNumFaces > 1) {
                faceLandmarkerHelper.maxNumFaces--
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.maxFacesPlus.setOnClickListener {
            if (faceLandmarkerHelper.maxNumFaces < 2) {
                faceLandmarkerHelper.maxNumFaces++
                updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate, false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {
                    try {
                        faceLandmarkerHelper.currentDelegate = p2
                        updateControlsUi()
                    } catch(e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "FaceLandmarkerHelper has not been initialized yet.")
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    private fun updateControlsUi() {
        // ... (Bu fonksiyon değişmedi) ...
        fragmentCameraBinding.bottomSheetLayout.maxFacesValue.text =
            faceLandmarkerHelper.maxNumFaces.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                faceLandmarkerHelper.minFaceDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                faceLandmarkerHelper.minFaceTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                faceLandmarkerHelper.minFacePresenceConfidence
            )
        backgroundExecutor.execute {
            faceLandmarkerHelper.clearFaceLandmarker()
            faceLandmarkerHelper.setupFaceLandmarker()
        }
        fragmentCameraBinding.overlay.clear()
    }

    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        // ... (ImageCapture eklendi, fonksiyon aynı) ...
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()
        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        detectFace(image)
                    }
                }
        imageCapture = ImageCapture.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()
        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer,
                imageCapture
            )
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectFace(imageProxy: ImageProxy) {
        faceLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // GÜNCELLENDİ: Ana mantık merkezi burası
    override fun onResults(
        resultBundle: FaceLandmarkerHelper.ResultBundle
    ) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                // Çekim işlemi devam ederken yeni bir tetiklemeyi engelle
                if (isCaptureInProgress) return@runOnUiThread

                // Blendshapes ve zamanlama (değişmedi)
                if (fragmentCameraBinding.recyclerviewResults.scrollState != SCROLL_STATE_DRAGGING) {
                    faceBlendshapesResultAdapter.updateResults(resultBundle.result)
                    faceBlendshapesResultAdapter.notifyDataSetChanged()
                }
                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                    String.format("%d ms", resultBundle.inferenceTime)

                // OverlayView'a sonuçları gönder
                fragmentCameraBinding.overlay.setResults(
                    resultBundle.result,
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )

                // YENİ: Aşamalı çekim koşullarını kontrol et
                checkCaptureConditions(resultBundle.result)

                // Çizim (değişmedi)
                fragmentCameraBinding.overlay.invalidate()
            }
        }
    }

    // Açı hesaplama (değişmedi)
    private fun rotationMatrixToAngles(matrix: FloatArray): Triple<Float, Float, Float> {
        if (matrix.size != 16) {
            return Triple(0f, 0f, 0f)
        }
        val m = matrix
        val pitchRad = atan2(m[9].toDouble(), m[10].toDouble())
        val yawRad = atan2(-m[8].toDouble(), sqrt(m[0].toDouble() * m[0].toDouble() + m[4].toDouble() * m[4].toDouble()))
        val rollRad = atan2(m[4].toDouble(), m[0].toDouble())

        return Triple(
            Math.toDegrees(pitchRad).toFloat(),
            Math.toDegrees(yawRad).toFloat(),
            Math.toDegrees(rollRad).toFloat()
        )
    }

    // YENİ: 3 aşamalı çekim mantığı
    private fun checkCaptureConditions(result: FaceLandmarkerResult) {
        // Poz ve landmark verilerini al
        val matrixes = result.facialTransformationMatrixes()
        if (!matrixes.isPresent || matrixes.get().isEmpty()) return

        // Yüz landmarkları yoksa devam etme
        if (result.faceLandmarks().isEmpty()) return

        val (pitch, yaw, roll) = rotationMatrixToAngles(matrixes.get()[0])
        val landmarks = result.faceLandmarks()[0]

        when (currentStage) {
            // GÜNCELLENDİ: Artık 3 açıyı da kontrol için gönderiyor
            1 -> checkStage1(landmarks, pitch, yaw, roll)
            // GÜNCELLENDİ: Pitch ve Roll eklendi
            2 -> checkStage2(landmarks, pitch, yaw, roll)
            // GÜNCELLENDİ: Pitch ve Roll eklendi
            3 -> checkStage3(landmarks, pitch, yaw, roll)
            // YENİ: Tepe Açısı (35 Derece)
            4 -> checkStage4(landmarks, pitch, yaw, roll)
        }
    }

    // YENİ: Aşama 1 - Yüzü ortala, elipsi doldur
    // GÜNCELLENDİ: pitch, yaw ve roll kontrolü eklendi
    private fun checkStage1(landmarks: List<NormalizedLandmark>, pitch: Float, yaw: Float, roll: Float) {
        // Yüzün elips içinde olup olmadığını ve doluluk oranını kontrol et
        val (isInside, fillRatio) = checkFaceInEllipse(landmarks)

        // Koşul 1: Yüz elipsin içinde mi?
        // Koşul 2: Doluluk oranı (%70'e yükseltildi)
        // Koşul 3: Kafa düz mü bakıyor? (Pitch, Yaw, Roll ~0 derece)
        val fillTarget = 0.70f // YENİ DEĞER: %60'tan %70'e
        val angleTolerance = 5f // %5 tolerans (5 derece olarak yorumlandı)
        val yawOk = abs(yaw) <= angleTolerance
        val pitchOk = abs(pitch) <= angleTolerance
        val rollOk = abs(roll) <= angleTolerance

        // GÜNCELLENDİ: Tüm koşullar kontrol ediliyor
        val conditionsMet = isInside && (fillRatio >= fillTarget) && yawOk && pitchOk && rollOk

        if (conditionsMet) {
            isCaptureInProgress = true // KİLİTLE
            fragmentCameraBinding.overlay.setInstruction("Harika!", true) // Elipsi yeşil yap
            takePhoto("STAGE_1_CENTER") {
                // Fotoğraf kaydedildikten sonra (cooldown ile):
                currentStage = 2
                activity?.runOnUiThread {
                    fragmentCameraBinding.overlay.setInstruction("Sağa Dön (45°)")
                }
            }
        } else {
            // GÜNCELLENDİ: Kullanıcıya net ipucu ver
            var instruction = "Yüzünü Ortala"
            if (!isInside) instruction = "Yüzünü Çerçeveye Al"
            else if (fillRatio < fillTarget) instruction = "Biraz Yaklaş (%70)"
            else if (!pitchOk || !rollOk) instruction = "Başını Dik Tut"
            else if (!yawOk) instruction = "Karşıya Bak"

            fragmentCameraBinding.overlay.setInstruction(instruction, false)
        }
    }

    // YENİ: Yüzün elips içinde olup olmadığını ve doluluk oranını kontrol etme
    // Dönen Değer: Pair<IsInside, FillRatio>
    private fun checkFaceInEllipse(landmarks: List<NormalizedLandmark>): Pair<Boolean, Float> {
        if (guideEllipseRect.isEmpty) return Pair(false, 0f)

        // Landmarkların ekran koordinatlarındaki bounding box'ını bul
        val w = fragmentCameraBinding.overlay.width
        val h = fragmentCameraBinding.overlay.height

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (landmark in landmarks) {
            val x = landmark.x() * w
            val y = landmark.y() * h
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }

        val faceRect = RectF(minX, minY, maxX, maxY)

        // 1. Koşul: Yüzün merkezi elipsin içinde mi?
        val isInside = guideEllipseRect.contains(faceRect.centerX(), faceRect.centerY())

        // 2. Koşul: Doluluk oranı - yüzün genişliğini elipsin genişliğine oranlıyoruz.
        val fillRatio = faceRect.width() / guideEllipseRect.width()

        return Pair(isInside, fillRatio)
    }

    // GÜNCELLENDİ: Aşama 2 - Sağa dönüşü, dik duruşu VE çerçeve içini kontrol et
    private fun checkStage2(landmarks: List<NormalizedLandmark>, pitch: Float, yaw: Float, roll: Float) {
        val targetYaw = 45f
        val yawTolerance = 7f // Tolerans 2.25'ten 7 dereceye GEVŞETİLDİ
        val angleTolerance = 10f // %5 (5 derece) dik duruş toleransı

        // YENİ: Yüzün çerçeve içinde olup olmadığını kontrol et
        val (isInside, _) = checkFaceInEllipse(landmarks)
        val yawCondition = yaw >= (targetYaw - yawTolerance) && yaw <= (targetYaw + yawTolerance)

        // YENİ: Kafa dik mi?
        val pitchOk = abs(pitch) <= angleTolerance
        val rollOk = abs(roll) <= angleTolerance

        // GÜNCELLENDİ: Tüm koşullar
        val conditionMet = yawCondition && isInside && pitchOk && rollOk

        if (conditionMet) {
            isCaptureInProgress = true // KİLİTLE
            fragmentCameraBinding.overlay.setInstruction("Sağ 45° Algılandı!", true)
            takePhoto("STAGE_2_RIGHT") {
                currentStage = 3
                activity?.runOnUiThread {
                    fragmentCameraBinding.overlay.setInstruction("Sola Dön (45°)")
                }
            }
        } else {
            // GÜNCELLENDİ: Talimatlar
            if (!isInside) {
                fragmentCameraBinding.overlay.setInstruction("Yüzünü Çerçeveye Al", false)
            } else if (!pitchOk || !rollOk) {
                fragmentCameraBinding.overlay.setInstruction("Başını Dik Tut", false)
            } else {
                fragmentCameraBinding.overlay.setInstruction("Sağa Dön (45°)", false)
            }
        }
    }

    // GÜNCELLENDİ: Aşama 3 - Sola dönüşü, dik duruşu VE çerçeve içini kontrol et
    private fun checkStage3(landmarks: List<NormalizedLandmark>, pitch: Float, yaw: Float, roll: Float) {
        val targetYaw = -45f
        val yawTolerance = 7f // Tolerans 2.25'ten 7 dereceye GEVŞETİLDİ
        val angleTolerance = 10f // %5 (5 derece) dik duruş toleransı

        // YENİ: Yüzün çerçeve içinde olup olmadığını kontrol et
        val (isInside, _) = checkFaceInEllipse(landmarks)
        val yawCondition = yaw >= (targetYaw - yawTolerance) && yaw <= (targetYaw + yawTolerance)

        // YENİ: Kafa dik mi?
        val pitchOk = abs(pitch) <= angleTolerance
        val rollOk = abs(roll) <= angleTolerance

        // GÜNCELLENDİ: Tüm koşullar
        val conditionMet = yawCondition && isInside && pitchOk && rollOk

        if (conditionMet) {
            isCaptureInProgress = true // KİLİTLE
            fragmentCameraBinding.overlay.setInstruction("Sol 45° Algılandı!", true)
            takePhoto("STAGE_3_LEFT") {
                currentStage = 4 // 4. Aşamaya geç
                activity?.runOnUiThread {
                    fragmentCameraBinding.overlay.setInstruction("Başını Eğ (35°)")
                }
            }
        } else {
            // GÜNCELLENDİ: Talimatlar
            if (!isInside) {
                fragmentCameraBinding.overlay.setInstruction("Yüzünü Çerçeveye Al", false)
            } else if (!pitchOk || !rollOk) {
                fragmentCameraBinding.overlay.setInstruction("Başını Dik Tut", false)
            } else {
                fragmentCameraBinding.overlay.setInstruction("Sola Dön (45°)", false)
            }
        }
    }


    // YENİ: Aşama 4 - Baş Eğme (Pitch 35 Derece) ve Kafa Tahmini Hizalama
    // YENİ: Aşama 4 - Baş Eğme (Pitch 30 Derece) ve Kafa Tahmini Hizalama
    // YENİ: Aşama 4 - Baş Eğme (Pitch 30 Derece) ve Kafa Tahmini Hizalama
    private fun checkStage4(landmarks: List<NormalizedLandmark>, pitch: Float, yaw: Float, roll: Float) {
        val targetPitch = 30f // Hedef açı: 30 derece (Mutlak değer)
        val pitchTolerance = 5f // Tolerans
        val angleTolerance = 10f 

        // Pitch değerinin mutlak değerini alıyoruz (Aşağı bakış negatif olabilir)
        val absPitch = abs(pitch)

        // 1. Açı Kontrolü
        val pitchCondition = abs(absPitch - targetPitch) <= pitchTolerance
        val yawOk = abs(yaw) <= angleTolerance
        val rollOk = abs(roll) <= angleTolerance

        // 2. Tahmini Kafa Hizalaması (Saçlı Deri Dahil)
        val (isAligned, alignmentMessage) = checkEstimatedHeadAlignment(landmarks)

        val conditionsMet = pitchCondition && isAligned && yawOk && rollOk

        if (conditionsMet) {
            isCaptureInProgress = true
            fragmentCameraBinding.overlay.setInstruction("Mükemmel! (30°)", true)
            takePhoto("STAGE_4_TILT_30") {
                currentStage = 1 // Başa dön
                activity?.runOnUiThread {
                    fragmentCameraBinding.overlay.setInstruction("Tamamlandı! Başa dön...")
                }
                view?.postDelayed({
                    if (_fragmentCameraBinding != null) {
                        fragmentCameraBinding.overlay.setInstruction("Yüzünü Ortala")
                    }
                }, 2000)
            }
        } else {
            if (!isAligned) {
                fragmentCameraBinding.overlay.setInstruction(alignmentMessage, false)
            } else if (!yawOk || !rollOk) {
                fragmentCameraBinding.overlay.setInstruction("Düz Dur", false)
            } else if (absPitch < targetPitch - pitchTolerance) {
                // Açı mutlak olarak küçük (örn 20°), daha fazla eğilmeli
                fragmentCameraBinding.overlay.setInstruction("Daha Fazla Eğ (30°)", false)
            } else if (absPitch > targetPitch + pitchTolerance) {
                // Açı mutlak olarak büyük (örn 40°), biraz kaldırmalı
                fragmentCameraBinding.overlay.setInstruction("Biraz Kaldır (30°)", false)
            } else {
                fragmentCameraBinding.overlay.setInstruction("Başını Eğ (30°)", false)
            }
        }
    }

    // YENİ: Yüz landmarklarından tüm kafayı (saç dahil) tahmin edip elipse hizalama
    // GÜNCELLENDİ: Sağ/Sol uç noktalara göre dikey hizalama ve tepe noktası kontrolü
    // YENİ: Yüz landmarklarından tüm kafayı (saç dahil) tahmin edip elipse hizalama
    // GÜNCELLENDİ: Anatomi tabanlı (En/Boy oranı) hesaplama
    // YENİ: Yüz landmarklarından tüm kafayı (saç dahil) tahmin edip elipse hizalama
    // GÜNCELLENDİ: "Top Head" (Saç Bölgesi) Odaklı Hizalama
    private fun checkEstimatedHeadAlignment(landmarks: List<NormalizedLandmark>): Pair<Boolean, String> {
        if (guideEllipseRect.isEmpty) return Pair(false, "Hata")

        val w = fragmentCameraBinding.overlay.width
        val h = fragmentCameraBinding.overlay.height

        // Landmarklar
        val leftCheek = landmarks[234]
        val rightCheek = landmarks[454]
        val topForehead = landmarks[10] // Alın Tepesi (Saç çizgisine yakın)

        // Piksel Koordinatları
        val minX = min(leftCheek.x(), rightCheek.x()) * w
        val maxX = max(leftCheek.x(), rightCheek.x()) * w
        val foreheadY = topForehead.y() * h

        // Yüz Genişliği
        val faceWidth = maxX - minX
        val faceCenterX = (minX + maxX) / 2f

        // Elips Bilgileri
        val ellipseCenterX = guideEllipseRect.centerX()
        val ellipseCenterY = guideEllipseRect.centerY()
        val ellipseWidth = guideEllipseRect.width()

        // 1. Yatay Hizalama
        val horizontalDiff = abs(faceCenterX - ellipseCenterX)
        val horizontalThreshold = ellipseWidth * 0.20f // Tolerans artırıldı
        if (horizontalDiff > horizontalThreshold) {
            return Pair(false, "Kafanı Ortala")
        }

        // 2. Mesafe/Zoom Kontrolü
        val widthRatio = faceWidth / ellipseWidth
        if (widthRatio < 0.60f) return Pair(false, "Yaklaş")
        if (widthRatio > 1.1f) return Pair(false, "Uzaklaş")

        // 3. Dikey Hizalama (Saç Bölgesi Odaklı)
        // Kullanıcı yüzün değil, "Top Head"in (Saçlı Deri) elips içinde olmasını istiyor.
        // Referans: Alın (Forehead).
        // Tahmin: Kafa tepesi, alından yüz genişliğinin %60'ı kadar yukarıdadır.
        val estimatedHairHeight = faceWidth * 0.6f
        val estimatedSkullTopY = foreheadY - estimatedHairHeight

        // Hedef Bölge Merkezi: Alın ile Tahmini Tepe'nin ortası
        val hairRegionCenterY = (foreheadY + estimatedSkullTopY) / 2f

        val verticalDiff = abs(hairRegionCenterY - ellipseCenterY)
        val verticalThreshold = guideEllipseRect.height() * 0.25f // %25 tolerans (daha rahat hizalama için)

        if (verticalDiff > verticalThreshold) {
            if (hairRegionCenterY < ellipseCenterY) {
                return Pair(false, "Aşağı İn") // Saç bölgesi çok yukarıda
            } else {
                return Pair(false, "Yukarı Çık") // Saç bölgesi çok aşağıda
            }
        }

        return Pair(true, "Mükemmel")
    }


    // GÜNCELLENDİ: Fotoğraf çekme fonksiyonu artık bir "onSaved" callback'i alıyor
    private fun takePhoto(stageIdentifier: String, onSaved: () -> Unit) {
        // imageCapture'ın ayarlandığından emin ol
        val imageCapture = this.imageCapture ?: return

        // Kayıt için dosya adı ve konumu oluştur
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "FACE_${stageIdentifier}_$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FaceLandmarker")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(requireContext().contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Fotoğraf çekme hatası: ${exc.message}", exc)
                    isCaptureInProgress = false // Hata durumunda KİLİDİ AÇ
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Fotoğraf kaydedildi: ${output.savedUri}")

                    if (_fragmentCameraBinding != null) {
                        fragmentCameraBinding.overlay.showCaptureSuccess()
                    }

                    // İşlem bittikten sonra (ve cooldown sonrası) KİLİDİ AÇ
                    view?.postDelayed({
                        onSaved() // Bir sonraki aşamaya geçiş talimatını çalıştır
                        isCaptureInProgress = false
                    }, 1500) // 1.5 saniye bekle
                }
            }
        )
    }

    override fun onEmpty() {
        if (_fragmentCameraBinding != null) {
            fragmentCameraBinding.overlay.clear()
            activity?.runOnUiThread {
                faceBlendshapesResultAdapter.updateResults(null)
                faceBlendshapesResultAdapter.notifyDataSetChanged()
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            faceBlendshapesResultAdapter.updateResults(null)
            faceBlendshapesResultAdapter.notifyDataSetChanged()

            if (errorCode == FaceLandmarkerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    FaceLandmarkerHelper.DELEGATE_CPU, false
                )
            }
        }
    }
}