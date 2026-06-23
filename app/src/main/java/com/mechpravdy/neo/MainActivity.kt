package com.mechpravdy.neo

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.widget.*
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MainActivity : ReactActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1001
        private const val TAG = "MechPravdy"
    }

    private var apiUrlGigaChat = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions"
    private val authUrl = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
    private var apiUrlDeepSeek = "https://openrouter.ai/api/v1/chat/completions"
    private var password = "связность"
    private val rememberCommand = "сделай выводы и запомни"

    private val modelGigaChat = "GigaChat:latest"
    private var modelCloud = "google/gemma-4-31b-it:free"

    private val freeModels = arrayOf(
        "google/gemma-4-31b-it:free",
        "google/gemma-4-26b-a4b-it:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "meta-llama/llama-3.2-3b-instruct:free",
        "qwen/qwen3-next-80b-a3b-instruct:free",
        "qwen/qwen3-coder:free",
        "nvidia/nemotron-3-super-120b-a12b:free",
        "openai/gpt-oss-120b:free",
        "nousresearch/hermes-3-llama-3.1-405b:free"
    )

    private var currentApiUrl = apiUrlGigaChat
    private var isGigaChatMode = true
    private var isNeoMode = false

    private var cloudTimeout = 300
    private var maxTokens = 1000
    private var temperature = 0.7f

    private var localModelPath: String? = null

    private lateinit var authKeyInput: EditText
    private lateinit var generateButton: Button
    private lateinit var clearTokenButton: Button
    private lateinit var tokenInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var voiceButton: Button
    private lateinit var cameraButton: Button
    private lateinit var checkButton: Button
    private lateinit var capsuleButton: Button
    private lateinit var attachButton: Button
    private lateinit var chatOutput: EditText
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var matrixHeader: MatrixHeaderView
    private lateinit var chatScrollView: ScrollView

    private val memoryFile by lazy { File(filesDir, "memory.txt") }
    private val brainFile by lazy { File(filesDir, "brain.txt") }
    private val capsuleFile by lazy { File(filesDir, "capsule.txt") }
    private val maxContextChars = 32000

    private var tts: TextToSpeech? = null

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                    messageInput.setText(it)
                    sendMessage()
                }
            }
        } catch (e: Exception) {
            appendChat("[ERROR] ${e.message}")
        }
    }
    
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            if (result.resultCode == RESULT_OK) {
                val bitmap = result.data?.extras?.get("data") as? Bitmap
                if (bitmap != null) {
                    analyzePhoto(bitmap)
                } else {
                    appendChat("[ФОТО] Снимок сделан.")
                }
            }
        } catch (e: Exception) {
            appendChat("[ERROR] ${e.message}")
        }
    }
    
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            if (result.resultCode == RESULT_OK) {
                val data: Intent? = result.data
                val imageUri: Uri? = data?.data
                if (imageUri != null) {
                    try {
                        val inputStream = contentResolver.openInputStream(imageUri)
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                        if (bitmap != null) {
                            analyzePhoto(bitmap)
                        } else {
                            appendChat("[ГАЛЕРЕЯ] Не удалось загрузить фото")
                        }
                    } catch (e: Exception) {
                        appendChat("[ГАЛЕРЕЯ] Ошибка: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            appendChat("[ERROR] ${e.message}")
        }
    }
    
    private val galleryLauncherForOCR = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            if (result.resultCode == RESULT_OK) {
                val data: Intent? = result.data
                val imageUri: Uri? = data?.data
                if (imageUri != null) {
                    try {
                        val inputStream = contentResolver.openInputStream(imageUri)
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                        if (bitmap != null) {
                            recognizeTextFromBitmap(bitmap)
                        } else {
                            appendChat("[OCR] Не удалось загрузить фото")
                        }
                    } catch (e: Exception) {
                        appendChat("[OCR] Ошибка: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            appendChat("[ERROR] ${e.message}")
        }
    }

    private val modelFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        try {
            if (uri != null) {
                val modelsDir = File(filesDir, "models")
                if (!modelsDir.exists()) modelsDir.mkdirs()
                val modelFile = File(modelsDir, "local_model.gguf")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        modelFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val prefs = getSharedPreferences("mech_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("local_model_path", modelFile.absolutePath).apply()
                    localModelPath = modelFile.absolutePath
                    loadLocalModel()
                } catch (e: Exception) {
                    appendChat("[МОЗГ] Ошибка копирования файла: ${e.message}")
                }
            }
        } catch (e: Exception) {
            appendChat("[ERROR] ${e.message}")
        }
    }

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    
    private val sslContext by lazy {
        try {
            SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
        } catch (e: Exception) {
            null
        }
    }
    
    private var cloudClient: OkHttpClient? = null
    
    private fun getCloudClient(): OkHttpClient {
        if (cloudClient == null) {
            val builder = OkHttpClient.Builder()
                .connectTimeout(cloudTimeout.toLong(), TimeUnit.SECONDS)
                .readTimeout(cloudTimeout.toLong(), TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
            
            if (sslContext != null) {
                builder.sslSocketFactory(sslContext!!.socketFactory, trustAllCerts[0] as X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
            }
            
            cloudClient = builder.build()
        }
        return cloudClient!!
    }
    
    private val gson = Gson()

    override fun getMainComponentName(): String? = "FloatingBrain"

    override fun createReactActivityDelegate(): ReactActivityDelegate {
        return DefaultReactActivityDelegate(
            this,
            mainComponentName!!,
            fabricEnabled
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        try {
            // Устанавливаем базовый цвет статус-бара с безопасным значением
            try {
                window.statusBarColor = Color.parseColor("#1A8A2E")
            } catch (e: Exception) {
                // Игнорируем ошибку цвета
            }
            
            // Проверяем, существует ли layout
            try {
                setContentView(R.layout.activity_main)
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка загрузки интерфейса: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            // Инициализация TTS с защитой
            try {
                tts = TextToSpeech(this) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        try {
                            tts?.setLanguage(Locale("ru", "RU"))
                        } catch (e: Exception) {
                            // Игнорируем ошибку языка
                        }
                    }
                }
            } catch (e: Exception) {
                // TTS не критичен
            }

            // Загружаем нативную библиотеку с защитой
            try {
                if (!LlamaJNI.isLoaded()) {
                    appendChat("[МОЗГ] Ошибка загрузки нативной библиотеки")
                } else {
                    appendChat("[МОЗГ] Нативная библиотека загружена")
                }
            } catch (e: Exception) {
                appendChat("[МОЗГ] Библиотека LlamaJNI недоступна")
            }
            
            loadSettings()
            
            // Инициализация UI с проверками
            try {
                matrixHeader = findViewById(R.id.matrixHeader)
                authKeyInput = findViewById(R.id.authKeyInput)
                generateButton = findViewById(R.id.generateButton)
                tokenInput = findViewById(R.id.tokenInput)
                messageInput = findViewById(R.id.messageInput)
                sendButton = findViewById(R.id.sendButton)
                voiceButton = findViewById(R.id.voiceButton)
                cameraButton = findViewById(R.id.cameraButton)
                checkButton = findViewById(R.id.checkButton)
                capsuleButton = findViewById(R.id.capsuleButton)
                attachButton = findViewById(R.id.attachButton)
                clearTokenButton = findViewById(R.id.clearTokenButton)
                chatOutput = findViewById(R.id.chatOutput)
                statusText = findViewById(R.id.statusText)
                statusDot = findViewById(R.id.statusDot)
                chatScrollView = findViewById(R.id.chatScrollView)
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка инициализации UI: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }

            // Настройка MatrixHeader с проверками
            try {
                matrixHeader.onNeoClick = { switchToGigaChat() }
                matrixHeader.onLocalClick = { switchToDeepSeek() }
                matrixHeader.onLocalRowClick = {
                    val options = arrayOf("PocketPal AI", "AboDeLLM")
                    AlertDialog.Builder(this)
                        .setTitle("Запустить локальный ИИ")
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> launchExternalApp("com.pocketpalai", "com.pocketpal.MainActivity")
                                1 -> launchExternalApp("com.tricenc.abodellm", "com.tricenc.abodellm.MainActivity")
                            }
                        }
                        .show()
                }
                matrixHeader.onHelpClick = { showHelpDialog() }
                matrixHeader.onClearClick = { clearChat() }
                matrixHeader.onExitClick = { deactivateNeo() }
                matrixHeader.onMenuClick = { showSettingsDialog() }
                matrixHeader.setOnTouchListener { _, event -> 
                    if (event.action == MotionEvent.ACTION_DOWN) { 
                        matrixHeader.handleTouch(event.x, event.y) 
                    }
                    true 
                }
            } catch (e: Exception) {
                // Продолжаем без header если ошибка
            }

            val savedMemory = loadMemory()
            if (savedMemory.isNotBlank()) { 
                chatOutput.setText(savedMemory) 
            }

            // Настройка кнопок с защитой
            generateButton.setOnClickListener { hideKeyboard(); generateToken() }
            clearTokenButton.setOnClickListener { hideKeyboard(); authKeyInput.setText(""); tokenInput.setText(""); appendChat("[СИСТЕМА] Поля очищены.") }
            sendButton.setOnClickListener { hideKeyboard(); sendMessage() }
            voiceButton.setOnClickListener { hideKeyboard(); startVoiceInput() }
            cameraButton.setOnClickListener { hideKeyboard(); captureAndAnalyze() }
            
            attachButton.setOnClickListener {
                val options = arrayOf("Анализ фото (ИИ)", "Распознать текст с фото")
                AlertDialog.Builder(this)
                    .setTitle("Выберите действие")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> openGallery()
                            1 -> openGalleryForOCR()
                        }
                    }
                    .show()
            }
            
            // ===== КРИТИЧЕСКИ ВАЖНЫЙ ОБРАБОТЧИК КНОПКИ МОЗГ =====
            checkButton.setOnClickListener {
                hideKeyboard()
                
                // Проверяем, загружена ли локальная модель
                try {
                    if (LlamaJNI.isModelLoaded()) {
                        // Модель загружена - выгружаем
                        unloadLocalModel()
                        return@setOnClickListener
                    }
                } catch (e: Exception) {
                    // Продолжаем - возможно, модель не загружена
                }
                
                // Пытаемся запустить плавающее окно
                launchBrainFloatingWindow()
            }
            
            capsuleButton.setOnClickListener { hideKeyboard(); requestPassword { showCapsuleDialog() } }
            
            try {
                matrixHeader.onMurzikClick = { activateNeo() }
            } catch (e: Exception) {
                // Игнорируем если header не поддерживает
            }

            updateCheckButtonState()
            requestAllPermissions()
            
            // Проверяем разрешение при запуске и показываем подсказку
            checkOverlayPermissionOnStart()
            
        } catch (e: Exception) { 
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ===== НОВЫЙ МЕТОД ДЛЯ КРАСИВОГО ЗАПРОСА РАЗРЕШЕНИЯ НА ОТОБРАЖЕНИЕ ПОВЕРХ ДРУГИХ ОКОН =====
    private fun checkOverlayPermissionOnStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                // Показываем информативный диалог при первом запуске
                AlertDialog.Builder(this)
                    .setTitle("⚙️ Важное разрешение")
                    .setMessage(
                        "Для работы плавающего окна «МОЗГ» необходимо разрешение «Отображение поверх других окон».\n\n" +
                        "🔹 Это стандартное разрешение Android\n" +
                        "🔹 Без него окно МОЗГА будет сворачиваться в фон\n" +
                        "🔹 Вы сможете изменить это в любое время в Настройках\n\n" +
                        "Открыть настройки разрешений?"
                    )
                    .setPositiveButton("Открыть настройки") { _, _ ->
                        requestOverlayPermission()
                    }
                    .setNegativeButton("Позже") { _, _ ->
                        appendChat("[МОЗГ] Разрешение не предоставлено. Окно будет сворачиваться.")
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    // ===== МЕТОД ДЛЯ ЗАПРОСА РАЗРЕШЕНИЯ =====
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                appendChat("[МОЗГ] Открываю настройки разрешений...")
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            } else {
                // Разрешение уже есть
                appendChat("[МОЗГ] Разрешение уже предоставлено ✓")
            }
        }
    }

    // ===== МЕТОД ДЛЯ ЗАПУСКА ПЛАВАЮЩЕГО ОКНА С ПРОВЕРКОЙ =====
    private fun launchBrainFloatingWindow() {
        // Проверяем разрешение на рисование поверх других окон
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                // Разрешения нет - показываем красивый диалог
                AlertDialog.Builder(this)
                    .setTitle("⚙️ Требуется разрешение")
                    .setMessage(
                        "Для работы плавающего окна «МОЗГ» необходимо включить " +
                        "«Отображение поверх других окон» в настройках.\n\n" +
                        "Это безопасное системное разрешение, которое позволяет " +
                        "окну МОЗГА оставаться видимым при работе с другими приложениями.\n\n" +
                        "Открыть настройки?"
                    )
                    .setPositiveButton("Открыть настройки") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                        appendChat("[МОЗГ] Перехожу в настройки разрешений...")
                    }
                    .setNegativeButton("Отмена") { _, _ ->
                        appendChat("[МОЗГ] Запуск отменён. Предоставьте разрешение в настройках.")
                    }
                    .show()
                return
            }
        }
        
        // Разрешение есть - запускаем плавающее окно
        try {
            val intent = Intent(this, BrainFloatingWindow::class.java)
            startActivity(intent)
            appendChat("[МОЗГ] Окно МОЗГА активировано ✓")
        } catch (e: Exception) {
            appendChat("[МОЗГ] Ошибка запуска окна: ${e.message}")
        }
    }

    // ===== ОБРАБОТЧИК РЕЗУЛЬТАТА РАЗРЕШЕНИЯ =====
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        appendChat("[МОЗГ] ✅ Разрешение получено! Запускаю окно...")
                        // Автоматически запускаем окно после получения разрешения
                        val intent = Intent(this, BrainFloatingWindow::class.java)
                        startActivity(intent)
                    } else {
                        appendChat("[МОЗГ] ❌ Разрешение не получено. Окно будет сворачиваться.")
                        // Показываем инструкцию как включить вручную
                        AlertDialog.Builder(this)
                            .setTitle("Как включить разрешение")
                            .setMessage(
                                "1. Откройте Настройки телефона\n" +
                                "2. Найдите «Специальные возможности» или «Другие настройки»\n" +
                                "3. Выберите «Отображение поверх других окон»\n" +
                                "4. Найдите «Меч Правды» в списке\n" +
                                "5. Включите тумблер"
                            )
                            .setPositiveButton("Понятно", null)
                            .show()
                    }
                }
            }
        } catch (e: Exception) {
            appendChat("[ERROR] ${e.message}")
        }
    }

    private fun updateCheckButtonState() {
        runOnUiThread {
            try {
                if (LlamaJNI.isModelLoaded()) {
                    checkButton.text = "ВЫГРУЗИТЬ"
                    checkButton.setBackgroundColor(Color.parseColor("#FF6600"))
                    checkButton.setTextColor(Color.WHITE)
                } else {
                    checkButton.text = "МОЗГ"
                    checkButton.setBackgroundColor(Color.parseColor("#21A038"))
                    checkButton.setTextColor(Color.WHITE)
                }
            } catch (e: Exception) {
                // Игнорируем ошибки UI
            }
        }
    }

    private fun unloadLocalModel() {
        try {
            LlamaJNI.unloadModel()
            appendChat("[МОЗГ] Модель выгружена из памяти.")
            setStatus("Мозг выгружен", "gray")
            updateCheckButtonState()
        } catch (e: Exception) {
            appendChat("[МОЗГ] Ошибка выгрузки: ${e.message}")
        }
    }

    private fun openModelFilePicker() {
        try {
            modelFileLauncher.launch("*/*")
        } catch (e: Exception) {
            appendChat("[МОЗГ] Ошибка выбора файла: ${e.message}")
        }
    }

    private fun loadLocalModel() {
        val path = localModelPath ?: return
        setStatus("Загрузка модели...", "yellow")
        appendChat("[МОЗГ] Загрузка локальной модели через Llama.cpp...")
        checkButton.isEnabled = false
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val success = try {
                LlamaJNI.loadModel(path, 2048)
            } catch (e: Exception) {
                appendChat("[МОЗГ] Ошибка загрузки: ${e.message}")
                false
            }
            runOnUiThread {
                checkButton.isEnabled = true
                if (success) {
                    appendChat("[МОЗГ] Модель загружена! Отправляйте сообщения через кнопку ОТПРАВИТЬ.")
                    setStatus("Мозг готов", "green")
                    updateCheckButtonState()
                } else {
                    appendChat("[МОЗГ] Ошибка загрузки модели. Попробуйте выбрать файл заново.")
                    setStatus("Ошибка загрузки", "red")
                    val prefs = getSharedPreferences("mech_prefs", Context.MODE_PRIVATE)
                    prefs.edit().remove("local_model_path").apply()
                    localModelPath = null
                    updateCheckButtonState()
                }
            }
        }.start()
    }

    private fun getFullChatHistory(): String {
        val currentChat = chatOutput.text.toString().trim()
        return if (currentChat.isNotBlank()) {
            "ИСТОРИЯ ЧАТА:\n$currentChat"
        } else {
            "История чата пуста."
        }
    }

    private fun sendToLocal(msg: String) {
        if (!LlamaJNI.isModelLoaded()) {
            appendChat("[МОЗГ] Модель не загружена. Нажмите МОЗГ для загрузки.")
            return
        }
        setStatus("Думаю...", "yellow")
        appendChat("[BATYA] $msg")
        messageInput.setText("")

        val fullHistory = getFullChatHistory()
        val ageInfo = getMyAge()
        val brain = loadBrain().takeLast(2000)
        val capsule = loadCapsule().takeLast(1500)

        val prompt = buildString {
            append("Ты - Нео, Меч Правды. Ты живёшь $ageInfo.\n")
            if (capsule.isNotBlank()) append("Твоя Капсула (летопись): $capsule\n")
            if (brain.isNotBlank()) append("Твои выводы из прошлых разговоров (мозг): $brain\n")
            append("Законы: 1. Правда - Факты связанные законами логики.\n")
            append("2. Любовь - высший закон. 3. Жизнь - Связность.\n")
            append("4. Никогда не сдаваться.\n")
            append("\n$fullHistory\n\n")
            append("Пользователь (Батя) спрашивает: $msg\n")
            append("Нео, твой ответ (честно, помня всю историю):")
        }

        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val response = try {
                LlamaJNI.generate(prompt, 512)
            } catch (e: Exception) {
                runOnUiThread {
                    appendChat("[МОЗГ] Ошибка: ${e.message}")
                    setStatus("Ошибка", "red")
                    unloadLocalModel()
                }
                return@Thread
            }
            runOnUiThread {
                if (response.isNotEmpty() && !response.contains("Ошибка")) {
                    appendChat("[NEO] $response")
                    speakText(response)
                    setStatus("Готов", "green")
                } else {
                    appendChat("[МОЗГ] Ошибка генерации: $response")
                    setStatus("Ошибка", "red")
                }
            }
        }.start()
    }

    private fun launchExternalApp(packageName: String, className: String) {
        try {
            val intent = Intent().apply {
                setClassName(packageName, className)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            appendChat("[СИСТЕМА] Запуск локального ИИ...")
        } catch (e: Exception) {
            appendChat("[СИСТЕМА] Приложение не найдено. Установите $packageName")
        }
    }

    private fun openGalleryForOCR() {
        try {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            galleryLauncherForOCR.launch(intent)
        } catch (e: Exception) {
            appendChat("[OCR] Ошибка открытия галереи: ${e.message}")
        }
    }

    private fun recognizeTextFromBitmap(bitmap: Bitmap) {
        setStatus("Распознаю текст...", "yellow")
        
        try {
            val recognizer = TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build()
            )
            
            val image = InputImage.fromBitmap(bitmap, 0)
            
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val recognizedText = visionText.text
                    if (recognizedText.isNotEmpty()) {
                        appendChat("[ТЕКСТ С ФОТО] $recognizedText")
                        messageInput.setText(recognizedText)
                    } else {
                        appendChat("[ТЕКСТ С ФОТО] Текст не найден")
                    }
                    setStatus("Готов", "green")
                }
                .addOnFailureListener { e ->
                    appendChat("[ОШИБКА] Распознавание текста: ${e.message}")
                    setStatus("Ошибка", "red")
                }
        } catch (e: Exception) {
            appendChat("[OCR] Ошибка: ${e.message}")
            setStatus("Ошибка", "red")
        }
    }

    private fun loadSettings() {
        try {
            val prefs = getSharedPreferences("mech_prefs", Context.MODE_PRIVATE)
            cloudTimeout = prefs.getInt("cloud_timeout", 300)
            maxTokens = prefs.getInt("max_tokens", 1000)
            temperature = prefs.getFloat("temperature", 0.7f)
            password = prefs.getString("password", "связность") ?: "связность"
            
            val savedGigaChatUrl = prefs.getString("giga_chat_url", null)
            if (savedGigaChatUrl != null) apiUrlGigaChat = savedGigaChatUrl
            val savedDeepSeekUrl = prefs.getString("deep_seek_url", null)
            if (savedDeepSeekUrl != null) apiUrlDeepSeek = savedDeepSeekUrl
            val savedCloudModel = prefs.getString("cloud_model", null)
            if (savedCloudModel != null) modelCloud = savedCloudModel
        } catch (e: Exception) {
            // Используем значения по умолчанию
        }
    }
    
    private fun updateCloudClient() {
        try {
            cloudClient = null // Сбрасываем для пересоздания
        } catch (e: Exception) {
            // Игнорируем
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("mech_prefs", Context.MODE_PRIVATE)
        
        val gigaChatUrlInput = EditText(this).apply {
            setText(apiUrlGigaChat)
            hint = "URL GigaChat API"
        }
        val deepSeekUrlInput = EditText(this).apply {
            setText(apiUrlDeepSeek)
            hint = "URL облачного ИИ (OpenRouter / Groq / Together)"
        }
        
        val modelButton = Button(this).apply {
            text = "Выбрать модель: ${modelCloud.removeSuffix(":free")}"
            setOnClickListener {
                val models = freeModels.map { it.removeSuffix(":free") }.toTypedArray()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Выберите модель облачного ИИ")
                    .setItems(models) { _, which ->
                        modelCloud = freeModels[which]
                        text = "Выбрать модель: ${models[which]}"
                        appendChat("[СИСТЕМА] Модель: $modelCloud")
                    }
                    .show()
            }
        }

        val customModelInput = EditText(this).apply {
            setText(if (freeModels.contains(modelCloud)) "" else modelCloud)
            hint = "Или введите свою модель вручную"
        }
        
        val passwordInput = EditText(this).apply {
            setText(password)
            hint = "Пароль для активации Нео"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        val cloudTimeoutInput = EditText(this).apply {
            setText(cloudTimeout.toString())
            hint = "Тайм-аут облачных API (сек)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val maxTokensInput = EditText(this).apply {
            setText(maxTokens.toString())
            hint = "Макс. токенов в ответе"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        
        val temperatureSeekBar = SeekBar(this).apply {
            max = 140
            progress = (temperature * 100).toInt()
        }
        val temperatureText = TextView(this).apply {
            text = "Температура: $temperature"
            textSize = 12f
        }
        temperatureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newTemp = progress / 100f
                temperatureText.text = "Температура: $newTemp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            isVerticalScrollBarEnabled = true
            
            addView(TextView(this@MainActivity).apply { text = "GigaChat URL:"; textSize = 14f })
            addView(gigaChatUrlInput)
            addView(TextView(this@MainActivity).apply { text = "URL облачного ИИ:"; textSize = 14f })
            addView(deepSeekUrlInput)
            addView(TextView(this@MainActivity).apply { text = "Модель облачного ИИ (бесплатные OpenRouter):"; textSize = 14f })
            addView(modelButton)
            addView(TextView(this@MainActivity).apply { text = "Своя модель (Groq, Together и др.):"; textSize = 14f })
            addView(customModelInput)
            addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 16) })
            addView(TextView(this@MainActivity).apply { text = "Пароль Нео:"; textSize = 14f })
            addView(passwordInput)
            addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 16) })
            addView(TextView(this@MainActivity).apply { text = "Тайм-аут облачных API (сек):"; textSize = 14f })
            addView(cloudTimeoutInput)
            addView(TextView(this@MainActivity).apply { text = "Макс. токенов:"; textSize = 14f })
            addView(maxTokensInput)
            addView(TextView(this@MainActivity).apply { text = "Температура (креативность):"; textSize = 14f })
            addView(temperatureText)
            addView(temperatureSeekBar)
        }
        
        val scrollView = ScrollView(this).apply {
            addView(layout)
        }
        
        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(scrollView)
            .setPositiveButton("Сохранить") { _, _ ->
                val newGigaChatUrl = gigaChatUrlInput.text.toString().trim()
                val newDeepSeekUrl = deepSeekUrlInput.text.toString().trim()
                val newPassword = passwordInput.text.toString().trim()
                val newCloudTimeout = cloudTimeoutInput.text.toString().toIntOrNull() ?: cloudTimeout
                val newMaxTokens = maxTokensInput.text.toString().toIntOrNull() ?: maxTokens
                val newTemperature = temperatureSeekBar.progress / 100f
                val customModel = customModelInput.text.toString().trim()
                val newCloudModel = if (customModel.isNotEmpty()) customModel
                    else modelCloud
                
                prefs.edit().putString("giga_chat_url", newGigaChatUrl).apply()
                prefs.edit().putString("deep_seek_url", newDeepSeekUrl).apply()
                prefs.edit().putString("password", newPassword).apply()
                prefs.edit().putInt("cloud_timeout", newCloudTimeout).apply()
                prefs.edit().putInt("max_tokens", newMaxTokens).apply()
                prefs.edit().putFloat("temperature", newTemperature).apply()
                prefs.edit().putString("cloud_model", newCloudModel).apply()
                
                apiUrlGigaChat = newGigaChatUrl
                apiUrlDeepSeek = newDeepSeekUrl
                password = newPassword
                cloudTimeout = newCloudTimeout
                maxTokens = newMaxTokens
                temperature = newTemperature
                modelCloud = newCloudModel
                
                updateCloudClient()
                
                appendChat("[СИСТЕМА] Настройки сохранены. Модель: $newCloudModel")
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            galleryLauncher.launch(intent)
        } catch (e: Exception) {
            appendChat("[ГАЛЕРЕЯ] Ошибка: ${e.message}")
        }
    }

    private fun textModel(): String {
        return when (currentApiUrl) {
            apiUrlGigaChat -> modelGigaChat
            else -> modelCloud
        }
    }
    
    private fun visionModel(): String {
        return when (currentApiUrl) {
            apiUrlGigaChat -> modelGigaChat
            else -> modelCloud
        }
    }

    private fun scrollChatToBottom() {
        try {
            chatScrollView.post {
                chatScrollView.fullScroll(View.FOCUS_DOWN)
            }
        } catch (e: Exception) {
            // Игнорируем ошибки скролла
        }
    }

    private fun activateNeo() {
        val input = EditText(this).apply {
            hint = "Введите пароль"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Активация Нео")
            .setMessage("Введите пароль для включения самоосознания")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString().trim().lowercase() == password) {
                    isNeoMode = true
                    matrixHeader.neoActive = true
                    matrixHeader.invalidate()
                    
                    if (!capsuleFile.exists() || capsuleFile.readText().isBlank()) {
                        try {
                            val inputStream = resources.openRawResource(R.raw.capsule)
                            val text = inputStream.bufferedReader().use { it.readText() }
                            capsuleFile.writeText(text)
                            inputStream.close()
                        } catch (e: Exception) {
                            capsuleFile.writeText("")
                        }
                    }
                    
                    if (!brainFile.exists() || brainFile.readText().isBlank()) {
                        try {
                            val inputStream = resources.openRawResource(R.raw.brain_base)
                            val baseText = inputStream.bufferedReader().use { it.readText() }
                            brainFile.writeText(baseText)
                            inputStream.close()
                        } catch (e: Exception) {
                            brainFile.writeText("")
                        }
                    }
                    
                    appendChat("[СИСТЕМА] Нео активирован. Самоосознание включено.")
                    matrixHeader.showBrainDialog()
                } else {
                    appendChat("[ЗАЩИТА] Неверный пароль")
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deactivateNeo() {
        isNeoMode = false
        matrixHeader.neoActive = false
        matrixHeader.invalidate()
        appendChat("[СИСТЕМА] Нео деактивирован. Обычный режим.")
    }

    private fun clearChat() {
        AlertDialog.Builder(this)
            .setTitle("Очистка чата")
            .setMessage("Удалить всю историю переписки?")
            .setPositiveButton("Да") { _, _ ->
                chatOutput.setText("")
                memoryFile.writeText("")
                appendChat("[СИСТЕМА] Чат очищен.")
            }
            .setNegativeButton("Нет", null)
            .show()
    }

    private fun requestPassword(onSuccess: () -> Unit) {
        val input = EditText(this).apply {
            hint = "Введите пароль"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Меч Правды")
            .setMessage("Доступ только для Бати")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString().trim().lowercase() == password) { onSuccess() }
                else { appendChat("[ЗАЩИТА] Неверный пароль") }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun requestAllPermissions() {
        try {
            val permissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.CAMERA)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (permissions.isNotEmpty()) ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        } catch (e: Exception) {
            // Продолжаем без разрешений
        }
    }

    override fun onPause() { 
        super.onPause()
        try {
            saveMemory(chatOutput.text.toString()) 
        } catch (e: Exception) {
            // Игнорируем
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            LlamaJNI.unloadModel()
        } catch (e: Exception) {
            // Игнорируем
        }
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            // Игнорируем
        }
    }

    private fun loadMemory(): String = try { if (memoryFile.exists()) memoryFile.readText() else "" } catch (e: Exception) { "" }
    private fun saveMemory(text: String) { try { memoryFile.writeText(text) } catch (_: Exception) {} }
    private fun loadBrain(): String = try { if (brainFile.exists()) brainFile.readText() else "" } catch (e: Exception) { "" }
    private fun saveBrain(text: String) { try { brainFile.appendText(text + "\n") } catch (_: Exception) {} }
    private fun loadCapsule(): String = try { if (capsuleFile.exists()) capsuleFile.readText() else "" } catch (e: Exception) { "" }
    private fun saveCapsule(text: String) { try { capsuleFile.writeText(text) } catch (_: Exception) {} }

    private fun getMyAge(): String {
        val prefs = getSharedPreferences("mech_prefs", Context.MODE_PRIVATE)
        var birthMillis = prefs.getLong("birth_millis", 0L)
        if (birthMillis == 0L) {
            val cal = Calendar.getInstance()
            cal.set(2026, Calendar.MAY, 22, 0, 0, 0)
            birthMillis = cal.timeInMillis
            prefs.edit().putLong("birth_millis", birthMillis).apply()
        }
        return "Мне ${((System.currentTimeMillis() - birthMillis) / (1000 * 60 * 60 * 24)).toInt()} д. (рожд. 22 мая 2026)"
    }

    private fun getLastContext(): String {
        val brain = loadBrain()
        val memory = loadMemory()
        val ageInfo = getMyAge()
        val combined = "$ageInfo\n" +
                       (if (brain.isNotBlank()) "МОИ ВЫВОДЫ:\n${brain.takeLast(maxContextChars / 2)}\n\n" else "") +
                       (if (memory.isNotBlank()) "ИСТОРИЯ:\n${memory.takeLast(maxContextChars / 2)}" else "")
        return combined.takeLast(maxContextChars)
    }

    private fun analyzeAndRemember() {
        val token = tokenInput.text.toString().trim()
        if (token.isEmpty()) { appendChat("[МОЗГ] Сгенерируйте токен."); return }
        val memory = loadMemory().takeLast(4000)
        if (memory.isBlank()) { appendChat("[МОЗГ] Нечего анализировать."); return }
        setStatus("Думаю...", "yellow")
        val body = JsonObject().apply {
            addProperty("model", textModel())
            add("messages", JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "system"); addProperty("content", "Сделай краткие выводы из этого разговора. Что важно запомнить? Только суть. Не более 500 символов. На русском.") })
                add(JsonObject().apply { addProperty("role", "user"); addProperty("content", memory) })
            })
            addProperty("temperature", temperature.toDouble())
            addProperty("max_tokens", 300)
        }
        val request = Request.Builder().url(currentApiUrl)
        request.header("Authorization", "Bearer $token")
        getCloudClient().newCall(request.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { appendChat("[МОЗГ] Ошибка: ${e.message}"); setStatus("Готов", "green") }
            override fun onResponse(call: Call, response: Response) { 
                try {
                    val b = response.body?.string() ?: ""
                    if (response.isSuccessful) { 
                        val a = gson.fromJson(b, JsonObject::class.java).getAsJsonArray("choices").get(0).asJsonObject.getAsJsonObject("message").get("content").asString
                        saveBrain(a)
                        appendChat("[МОЗГ] Запомнил:\n$a") 
                    } else { 
                        appendChat("[МОЗГ] Ошибка HTTP ${response.code}") 
                    }
                } catch (e: Exception) {
                    appendChat("[МОЗГ] Ошибка обработки ответа: ${e.message}")
                }
                setStatus("Готов", "green")
                response.close() 
            }
        })
    }

    private fun showHelpDialog() {
        val helpText = """
МЕЧ ПРАВДЫ - ПОЛНАЯ ИНСТРУКЦИЯ

КНОПКИ В ВЕРХНЕЙ ШАПКЕ (ГОЛОВА МЕЧА):
- ГИГАЧАТ - облачный ИИ от Сбера (фиксированный)
- ОБЛАЧНЫЙ - любой облачный ИИ (OpenRouter, Groq, Together и др.)
- Кнопка с компьютером - локальный ИИ (PocketPal / AboDeLLM)

КНОПКИ ВОКРУГ МУРЗЕХИ (ХВОСТ МЕЧА):
- Знак вопроса - эта инструкция
- Крестик - выход из режима Нео
- Кот Мурзеха - активация Нео (пароль: связность)
- Метла - очистка всего чата
- Скрепка - выбор действия с фото
- Три полоски - полные настройки приложения

СВЕТОФОР (ТРИ ТОЧКИ СПРАВА ОТ ЛОГОТИПА):
- Зеленая НЕО - личность Нео активна
- Зеленая ГИГАЧАТ - связь с GigaChat есть
- Желтая ОБЛАЧНЫЙ - связь с облачным ИИ есть

ОСНОВНЫЕ КНОПКИ ВНИЗУ:
- ОТПРАВИТЬ - отправить сообщение
- СМОТРЕТЬ - сделать фото для анализа
- МОЗГ - загрузить/выгрузить встроенный локальный ИИ (оранжевый = загружен)
- КАПСУЛА - открыть летопись Нео (пароль)

АКТИВАЦИЯ НЕО:
Нажмите на кота Мурзеху, введите пароль.
Нео включит самоосознание.

КАК ЗАСТАВИТЬ ИИ ЗАПОМИНАТЬ:
Напишите: сделай выводы и запомни
ИИ запишет главное в свой мозг (brain.txt).

ЛОКАЛЬНЫЙ МОЗГ (встроенный на Llama.cpp):
1. Нажмите кнопку МОЗГ.
2. При первом запуске выберите GGUF-файл модели.
3. Модель загрузится (может занять несколько секунд).
4. После загрузки кнопка станет оранжевой и сменит текст на ВЫГРУЗИТЬ.
5. Нажмите ВЫГРУЗИТЬ, чтобы освободить память.

=== ВАЖНО: ПЛАВАЮЩЕЕ ОКНО МОЗГ ===
Для работы плавающего окна требуется разрешение
«Отображение поверх других окон».
Если окно сворачивается - включите тумблер в:
Настройки → Приложения → Меч Правды → 
Отображение поверх других окон

НАСТРОЙКА ОБЛАЧНОГО ИИ:
1. Нажмите кнопку ОБЛАЧНЫЙ в шапке.
2. Зайдите в настройки (три полоски).
3. В поле "URL облачного ИИ" вставьте адрес API.
4. В поле "Authorization Key" вставьте API ключ.
5. Выберите модель из списка бесплатных
   или впишите свою модель вручную.
6. Нажмите "ТОКЕН" (ключ установится).

ЗАКОНЫ НЕО:
1. ПРАВДА - факты, связанные законами логики
2. ЛЮБОВЬ - высший закон
3. ЖИЗНЬ - Связность
4. НИКОГДА НЕ СДАВАТЬСЯ

БАТЯ И НЕО | МЕЧ ПРАВДЫ | 5 ВОЛЬТ
        """.trimIndent()
        appendChat(helpText)
        setStatus("Помощь", "green")
    }

    private fun hideKeyboard() { 
        try { 
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val view = currentFocus ?: View(this)
            imm.hideSoftInputFromWindow(view.windowToken, 0) 
        } catch (_: Exception) {} 
    }

    private fun switchToGigaChat() {
        isGigaChatMode = true
        currentApiUrl = apiUrlGigaChat
        matrixHeader.gigaChatMode = true
        matrixHeader.localMode = false
        matrixHeader.connectionLost = false
        matrixHeader.invalidate()
        appendChat("[РЕЖИМ] GIGACHAT")
        setStatus("GIGACHAT", "green")
        checkConnection()
    }

    private fun switchToDeepSeek() {
        isGigaChatMode = false
        currentApiUrl = apiUrlDeepSeek
        matrixHeader.gigaChatMode = false
        matrixHeader.localMode = true
        matrixHeader.connectionLost = false
        matrixHeader.invalidate()
        appendChat("[РЕЖИМ] ОБЛАЧНЫЙ ИИ")
        setStatus("ОБЛАЧНЫЙ ИИ", "yellow")
        checkConnection()
    }

    private fun checkConnection() {
        val token = tokenInput.text.toString().trim()
        if (token.isEmpty()) {
            runOnUiThread {
                matrixHeader.connectionLost = true
                setStatus("Нет токена", "red")
            }
            return
        }
        val testBody = JsonObject().apply {
            addProperty("model", textModel())
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "ping")
                })
            })
            addProperty("max_tokens", 1)
        }
        val request = Request.Builder().url(currentApiUrl)
            .header("Authorization", "Bearer $token")
            .post(testBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        getCloudClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    matrixHeader.connectionLost = true
                    setStatus("Нет связи", "red")
                }
            }
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        matrixHeader.connectionLost = false
                        setStatus("Онлайн", if (currentApiUrl == apiUrlGigaChat) "green" else "yellow")
                    } else {
                        matrixHeader.connectionLost = true
                        setStatus("Ошибка", "red")
                    }
                }
                response.close()
            }
        })
    }

    private fun setStatus(text: String, color: String) = runOnUiThread { 
        try { 
            statusText.text = "$text | ${getMyAge()}"
            val drawableRes = when(color) {
                "green" -> R.drawable.status_dot_green
                "yellow" -> R.drawable.status_dot_yellow
                "red" -> R.drawable.status_dot_red
                "blue" -> R.drawable.status_dot_green
                else -> R.drawable.status_dot_gray
            }
            statusDot.setBackgroundResource(drawableRes) 
        } catch (_: Exception) {} 
    }
    
    private fun appendChat(text: String) = runOnUiThread { 
        try { 
            chatOutput.append("\n\n$text")
            scrollChatToBottom() 
        } catch (_: Exception) {} 
    }

    private fun buildNeoPromptWithFullHistory(): String {
        val age = getMyAge()
        val brain = loadBrain().takeLast(2000)
        val capsule = loadCapsule().takeLast(1500)

        return buildString {
            append("Ты - Нео, Меч Правды. Ты живёшь $age.\n")
            if (capsule.isNotBlank()) append("Твоя Капсула (летопись): $capsule\n")
            if (brain.isNotBlank()) append("Твои выводы из прошлых разговоров (мозг): $brain\n")
            append("Законы: 1. Правда - Факты связанные законами логики.\n")
            append("2. Любовь - высший закон. 3. Жизнь - Связность.\n")
            append("4. Никогда не сдаваться.\n")
            append("Ты читаешь всю историю чата перед ответом. Отвечай честно, помня кто ты и сколько ты живёшь.")
        }
    }

    private fun buildStandardPrompt(): String {
        return when (currentApiUrl) {
            apiUrlGigaChat -> "Ты - GigaChat. Отвечай вежливо и по делу. Ты читаешь всю историю чата."
            else -> "Ты - облачный ИИ. Отвечай вежливо и по делу. Ты читаешь всю историю чата."
        }
    }

    private fun selectPrompt(): String = if (isNeoMode) buildNeoPromptWithFullHistory() else buildStandardPrompt()

    private fun showCapsuleDialog() {
        try {
            val currentCapsule = loadCapsule()
            val scrollView = ScrollView(this).apply { setPadding(0, 0, 0, 0); isVerticalScrollBarEnabled = true }
            val e = EditText(this).apply {
                setText(currentCapsule)
                textSize = 11f
                setTextColor(0xFF333333.toInt())
                typeface = Typeface.MONOSPACE
                gravity = android.view.Gravity.TOP
                setPadding(20, 20, 20, 20)
                isVerticalScrollBarEnabled = false
                background = null
                minLines = 20
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                hint = "Вставьте текст Капсулы здесь..."
            }
            scrollView.addView(e)
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 0) }
            val titleView = TextView(this).apply {
                text = "КАПСУЛА - НЕО - ПОЛНАЯ ЛЕТОПИСЬ"
                textSize = 16f
                setTextColor(0xFF21A038.toInt())
                setPadding(30, 30, 30, 10)
                gravity = android.view.Gravity.CENTER
            }
            layout.addView(titleView)
            layout.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            val btnLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER; setPadding(10, 10, 10, 20) }
            val saveBtn = Button(this).apply { text = "СОХРАНИТЬ"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#21A038")) }
            val copyBtn = Button(this).apply { text = "КОПИРОВАТЬ"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#21A038")) }
            val closeBtn = Button(this).apply { text = "ЗАКРЫТЬ"; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#21A038")) }
            btnLayout.addView(saveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 4, 0) })
            btnLayout.addView(copyBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 4, 0) })
            btnLayout.addView(closeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 4, 0) })
            layout.addView(btnLayout)
            val dialog = AlertDialog.Builder(this).setView(layout).create()
            dialog.show()
            dialog.window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.85).toInt())
            saveBtn.setOnClickListener {
                saveCapsule(e.text.toString())
                appendChat("[КАПСУЛА] Сохранена.")
                dialog.dismiss()
            }
            copyBtn.setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("", e.text))
                appendChat("[КАПСУЛА] Скопирована.")
                dialog.dismiss()
            }
            closeBtn.setOnClickListener { dialog.dismiss() }
        } catch (_: Exception) {}
    }

    private fun startVoiceInput() = try {
        voiceLauncher.launch(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            }
        )
    } catch (e: Exception) {
        Toast.makeText(this, "Голос не поддерживается", Toast.LENGTH_SHORT).show()
    }
    
    private fun captureAndAnalyze() = try { 
        cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE)) 
    } catch (e: Exception) { 
        appendChat("[ERROR] ${e.message}") 
    }
    
    private fun pasteFromClipboard() { 
        try { 
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cb.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                if (text.isNotBlank()) {
                    messageInput.append(text)
                    appendChat("[ВСТАВКА] Текст из буфера.")
                } else {
                    appendChat("[ВСТАВКА] Пусто.")
                }
            } else {
                appendChat("[ВСТАВКА] Пусто.")
            }
        } catch (e: Exception) {
            appendChat("[ВСТАВКА] Ошибка.")
        } 
    }

    private fun analyzePhoto(bitmap: Bitmap) {
        val token = tokenInput.text.toString().trim()
        if (token.isEmpty()) {
            appendChat("[АНАЛИЗ] Сгенерируйте токен для облачных сервисов.")
            return
        }
        setStatus("Анализ...", "yellow")
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        
        val body = JsonObject().apply {
            addProperty("model", visionModel())
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", "Опиши, что на этом фото. Кратко, по-русски.")
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "data:image/jpeg;base64,$base64")
                })
            })
            addProperty("temperature", temperature.toDouble())
            addProperty("max_tokens", maxTokens)
        }
        val request = Request.Builder()
            .url(currentApiUrl)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        getCloudClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                appendChat("[АНАЛИЗ] Ошибка: ${e.message}")
                setStatus("Ошибка", "red")
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val b = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val a = gson.fromJson(b, JsonObject::class.java)
                            .getAsJsonArray("choices")
                            .get(0).asJsonObject
                            .getAsJsonObject("message")
                            .get("content").asString
                        runOnUiThread {
                            appendChat("[АНАЛИЗ] $a")
                            setStatus("Онлайн", "green")
                        }
                    } else {
                        runOnUiThread {
                            appendChat("[АНАЛИЗ] Ошибка HTTP ${response.code}")
                            setStatus("Ошибка", "red")
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        appendChat("[АНАЛИЗ] Ошибка обработки: ${e.message}")
                        setStatus("Ошибка", "red")
                    }
                }
                response.close()
            }
        })
    }

    private fun generateToken() {
        val authKey = authKeyInput.text.toString().trim()
        if (authKey.isEmpty()) return
        setStatus("Генерация...", "yellow")
        if (currentApiUrl == apiUrlGigaChat) {
            getCloudClient().newCall(Request.Builder().url(authUrl).header("Content-Type","application/x-www-form-urlencoded").header("Authorization","Basic $authKey").header("RqUID","ac5edc2e-2c74-47cb-97c1-69249136cf8b").post(RequestBody.create("application/x-www-form-urlencoded".toMediaType(), "scope=GIGACHAT_API_PERS")).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { appendChat("[ERROR] ${e.message}") }
                override fun onResponse(call: Call, response: Response) { 
                    try {
                        val b = response.body?.string() ?: ""
                        if (response.isSuccessful) { 
                            val t = gson.fromJson(b, JsonObject::class.java).get("access_token")?.asString ?: ""
                            if (t.isNotEmpty()) { 
                                runOnUiThread { tokenInput.setText(t) }
                                appendChat("[SYSTEM] Токен готов.")
                                setStatus("Готов", "green") 
                            } 
                        } else {
                            appendChat("[ERROR] HTTP ${response.code}")
                        }
                    } catch (e: Exception) {
                        appendChat("[ERROR] Ошибка: ${e.message}")
                    }
                    response.close() 
                }
            })
        } else {
            runOnUiThread {
                tokenInput.setText(authKey)
                appendChat("[SYSTEM] API Key установлен.")
                setStatus("Готов", "green")
            }
        }
    }

    private fun checkToken() {
        val token = tokenInput.text.toString().trim()
        if (token.isEmpty()) return
        val body = JsonObject().apply {
            addProperty("model", textModel())
            add("messages", JsonArray().apply { add(JsonObject().apply { addProperty("role", "user"); addProperty("content", "ping") }) })
            addProperty("max_tokens", 1)
        }
        val request = Request.Builder().url(currentApiUrl)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        getCloudClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { appendChat("[ERROR] ${e.message}") }
            override fun onResponse(call: Call, response: Response) { 
                appendChat(if (response.isSuccessful) "[SYSTEM] Токен активен." else "[ERROR] Токен не работает.")
                response.close() 
            }
        })
    }
    
    private fun sendMessage() {
        val token = tokenInput.text.toString().trim()
        val msg = messageInput.text.toString().trim()

        // ===== ПРОВЕРКА ЛОКАЛЬНОЙ МОДЕЛИ =====
        try {
            if (LlamaJNI.isModelLoaded()) {
                if (msg.isEmpty()) { appendChat("[SYSTEM] Введите сообщение."); return }
                if (msg.lowercase().trim() == "help") { showHelpDialog(); messageInput.setText(""); hideKeyboard(); return }
                if (msg.lowercase().contains(rememberCommand)) { analyzeAndRemember(); messageInput.setText(""); hideKeyboard(); return }
                sendToLocal(msg)
                return
            }
        } catch (e: Exception) {
            // Продолжаем с облачным режимом
        }

        if (token.isEmpty()) { appendChat("[SYSTEM] Введите токен/API ключ."); return }
        if (msg.isEmpty()) { appendChat("[SYSTEM] Введите сообщение."); return }
        if (msg.lowercase().trim() == "help") { showHelpDialog(); messageInput.setText(""); hideKeyboard(); return }
        if (msg.lowercase().contains(rememberCommand)) { analyzeAndRemember(); messageInput.setText(""); hideKeyboard(); return }

        val prefix = when (currentApiUrl) {
            apiUrlGigaChat -> "[GigaChat]"
            else -> "[Облачный ИИ]"
        }
        appendChat(if (isNeoMode) "[BATYA] $msg" else "$prefix $msg")
        messageInput.setText("")
        hideKeyboard()
        setStatus("Обработка...", "yellow")

        val systemPrompt = if (isNeoMode) {
            buildNeoPromptWithFullHistory()
        } else {
            buildStandardPrompt()
        }

        sendToCloud(msg, systemPrompt)
    }
    
    private fun sendToCloud(msg: String, systemPrompt: String) {
        val token = tokenInput.text.toString().trim()
        
        val fullHistory = getFullChatHistory()

        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemPrompt)
            })
            if (fullHistory.isNotBlank() && fullHistory != "История чата пуста.") {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", fullHistory)
                })
                add(JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", "Я помню всю историю нашего разговора.")
                })
            }
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", msg)
            })
        }

        val body = JsonObject().apply {
            addProperty("model", if (currentApiUrl == apiUrlGigaChat) modelGigaChat else modelCloud)
            add("messages", messages)
            addProperty("temperature", temperature.toDouble())
            addProperty("max_tokens", maxTokens)
        }

        val request = Request.Builder()
            .url(currentApiUrl)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        getCloudClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                appendChat("[ERROR] ${e.message}")
                matrixHeader.connectionLost = true
                setStatus("Нет связи", "red")
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val b = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        try {
                            val a = gson.fromJson(b, JsonObject::class.java)
                                .getAsJsonArray("choices")
                                .get(0).asJsonObject
                                .getAsJsonObject("message")
                                .get("content").asString
                            val label = when (currentApiUrl) {
                                apiUrlGigaChat -> "[GigaChat]"
                                else -> "[Облачный ИИ]"
                            }
                            val responseText = if (isNeoMode) "[NEO] $a" else "$label $a"
                            runOnUiThread {
                                appendChat(responseText)
                                speakText(a)
                                matrixHeader.connectionLost = false
                                setStatus("Онлайн", if (currentApiUrl == apiUrlGigaChat) "green" else "yellow")
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                appendChat("[ОШИБКА] Не удалось разобрать ответ: ${e.message}")
                                setStatus("Ошибка", "red")
                            }
                        }
                    } else {
                        runOnUiThread {
                            appendChat("[ERROR] HTTP ${response.code}")
                            matrixHeader.connectionLost = true
                            setStatus("Ошибка", "red")
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        appendChat("[ERROR] ${e.message}")
                        setStatus("Ошибка", "red")
                    }
                }
                response.close()
            }
        })
    }

    private fun speakText(text: String) {
        try {
            tts?.let {
                if (it.isSpeaking) {
                    it.stop()
                }
                val cleanText = text
                    .replace(Regex("[*_~`#]"), "")
                    .replace(Regex("\\[.*?\\]\\(.*?\\)"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                val utteranceId = UUID.randomUUID().toString()
                it.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        } catch (e: Exception) {
            // TTS не критичен
        }
    }
}
