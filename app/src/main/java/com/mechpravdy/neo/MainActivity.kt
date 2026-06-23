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
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                messageInput.setText(it)
                sendMessage()
            }
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
    }
    
    private val galleryLauncherForOCR = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
    }

    private val modelFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
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
    }

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    private val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
    
    private var cloudClient = OkHttpClient.Builder()
        .connectTimeout(cloudTimeout.toLong(), TimeUnit.SECONDS)
        .readTimeout(cloudTimeout.toLong(), TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()
    
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
            window.statusBarColor = Color.parseColor("#1A8A2E")
            setContentView(R.layout.activity_main)

            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Locale("ru", "RU"))
                }
            }

            // Загружаем нативную библиотеку
            if (!LlamaJNI.isLoaded()) {
                appendChat("[МОЗГ] Ошибка загрузки нативной библиотеки")
            } else {
                appendChat("[МОЗГ] Нативная библиотека загружена")
            }
            
            loadSettings()
            
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
            matrixHeader.setOnTouchListener { _, event -> if (event.action == MotionEvent.ACTION_DOWN) { matrixHeader.handleTouch(event.x, event.y) }; true }

            val savedMemory = loadMemory()
            if (savedMemory.isNotBlank()) { chatOutput.setText(savedMemory) }

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
            
            // ===== НОВЫЙ ОБРАБОТЧИК КНОПКИ МОЗГ =====
            checkButton.setOnClickListener {
                hideKeyboard()
                
                // Проверяем разрешение на рисование поверх других окон
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.canDrawOverlays(this)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                        appendChat("[МОЗГ] Требуется разрешение для плавающего окна")
                        return@setOnClickListener
                    }
                }
                
                // Запускаем плавающее React Native окно
                val intent = Intent(this, BrainFloatingWindow::class.java)
                startActivity(intent)
                appendChat("[МОЗГ] Окно МОЗГА активировано")
            }
            
            capsuleButton.setOnClickListener { hideKeyboard(); requestPassword { showCapsuleDialog() } }
            matrixHeader.onMurzikClick = { activateNeo() }

            updateCheckButtonState()

            requestAllPermissions()
        } catch (e: Exception) { Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    // ===== ОБРАБОТЧИК РЕЗУЛЬТАТА РАЗРЕШЕНИЯ =====
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    appendChat("[МОЗГ] Разрешение получено. Запускаю окно...")
                    val intent = Intent(this, BrainFloatingWindow::class.java)
                    startActivity(intent)
                } else {
                    appendChat("[МОЗГ] Разрешение не получено. Повторите попытку.")
                }
            }
        }
    }

    private fun updateCheckButtonState() {
        runOnUiThread {
            if (LlamaJNI.isModelLoaded()) {
                checkButton.text = "ВЫГРУЗИТЬ"
                checkButton.setBackgroundColor(Color.parseColor("#FF6600"))
                checkButton.setTextColor(Color.WHITE)
            } else {
                checkButton.text = "МОЗГ"
                checkButton.setBackgroundColor(Color.parseColor("#21A038"))
                checkButton.setTextColor(Color.WHITE)
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
        modelFileLauncher.launch("*/*")
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
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        galleryLauncherForOCR.launch(intent)
    }

    private fun recognizeTextFromBitmap(bitmap: Bitmap) {
        setStatus("Распознаю текст...", "yellow")
        
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
    }

    private fun loadSettings() {
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
        
        updateCloudClient()
    }
    
    private fun updateCloudClient() {
        cloudClient = OkHttpClient.Builder()
            .connectTimeout(cloudTimeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(cloudTimeout.toLong(), TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
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
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        galleryLauncher.launch(intent)
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
        chatScrollView.post {
            chatScrollView.fullScroll(View.FOCUS_DOWN)
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
    }

    override fun onPause() { super.onPause(); saveMemory(chatOutput.text.toString()) }
    override fun onDestroy() {
        super.onDestroy()
        LlamaJNI.unloadModel()
        tts?.stop()
        tts?.shutdown()
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
        cloudClient.newCall(request.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { appendChat("[МОЗГ] Ошибка: ${e.message}"); setStatus("Готов", "green") }
            override fun onResponse(call: Call, response: Response) { val b = response.body?.string() ?: ""; if (response.isSuccessful) { val a = gson.fromJson(b, JsonObject::class.java).getAsJsonArray("choices").get(0).asJsonObject.getAsJsonObject("message").get("content").asString; saveBrain(a); appendChat("[МОЗГ] Запомнил:\n$a") } else { appendChat("[МОЗГ] Ошибка HTTP ${response.code}") }; setStatus("Готов", "green"); response.close() }
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

=== ВАЖНО: ИСТОРИЯ ЧАТА ===
Локальная и облачные модели ВИДЯТ всю историю чата.
Вы можете попросить: "посмотри вверху" или "вспомни, что мы обсуждали".
Модели прочитают всё, что было до этого.

НАСТРОЙКА ОБЛАЧНОГО ИИ:
1. Нажмите кнопку ОБЛАЧНЫЙ в шапке.
2. Зайдите в настройки (три полоски).
3. В поле "URL облачного ИИ" вставьте адрес API:
   - OpenRouter: https://openrouter.ai/api/v1/chat/completions
   - Groq: https://api.groq.com/openai/v1/chat/completions
   - Together: https://api.together.xyz/v1/chat/completions
4. В поле "Authorization Key" вставьте API ключ.
5. Выберите модель из списка бесплатных (для OpenRouter)
   или впишите свою модель вручную (для Groq/Together).
6. Нажмите "ТОКЕН" (ключ установится).
Готово! Можно общаться через любой облачный сервис.

БЕСПЛАТНЫЕ МОДЕЛИ OPENROUTER:
В настройках доступен список из 9 бесплатных моделей,
включая Google Gemma 4, Meta Llama 3.3, NVIDIA Nemotron.
Выбирайте любую — они не тратят кредиты.

РУЧНОЙ ВВОД МОДЕЛИ:
Для Groq впишите: llama-3.3-70b-versatile
Для Together впишите: meta-llama/Llama-3.3-70B-Instruct-Turbo
Или любую другую модель, которую поддерживает ваш провайдер.

ЗАПУСК ВНЕШНЕГО ЛОКАЛЬНОГО ИИ:
Нажмите кнопку с компьютером, выберите PocketPal или AboDeLLM.
В приложении выберите модель, вставьте Капсулу в System Prompt.
Локальный ИИ работает без интернета.

PocketPal AI Полное руководство по всем функциям
Введение

PocketPal AI — это приложение, которое позволяет запускать
языковые модели (LLM) непосредственно на вашем телефоне без 
необходимости подключения к интернету. Все данные обрабатываются
локально на устройстве, что обеспечивает полную конфиденциальность .

Главное меню (☰)

При запуске приложения нажмите на иконку 
меню (три полоски) в левом верхнем углу. Меню содержит
следующие разделы :

1. Chat (Чат)

Основной раздел для общения с ИИ.

Как использовать:

Убедитесь, что модель загружена в память
Перейдите на страницу Chat из меню
Введите сообщение в поле ввода в нижней части экрана
Нажмите стрелку отправки
ИИ начнёт генерировать ответ 

Дополнительные возможности чата:

Редактирование сообщений: Нажмите и удерживайте своё
сообщение для редактирования, затем повторите 
генерацию ответа

Копирование текста: Нажмите иконку копирования
внизу ответа ИИ для копирования всего ответа; для 
копирования отдельного абзаца — нажмите
и удерживайте его

Управление чатами: Доступны массовое удаление
и экспорт чатов

Во время генерации: Экран не выключается, 
пока ИИ генерирует ответ 

Выбор модели в чате: На левой стороне поля ввода
есть иконка шеврона (v), через которую можно
выбрать и загрузить модель .

2. Models (Модели)

Раздел для управления моделями.

Загрузка модели:

Перейдите на страницу Models

Выберите модель из списка рекомендованных
(Danube, Phi, Gemma 2, Qwen)
Нажмите кнопку Download

Добавление модели из Hugging Face:

Нажмите кнопку + (плюс) в правом нижнем углу
Выберите Add from Hugging Face
Выполните поиск нужной модели
Выберите GGUF-файл и нажмите Download 

Добавление локальной модели:

Нажмите + → Add Local Model
Выберите GGUF-файл в файловом менеджере 
устройства

Загрузка модели в память:

После завершения загрузки нажмите кнопку
Load рядом с моделью

Дождитесь окончания загрузки — статус 
изменится на "Loaded"

Приложение автоматически выгружает модель из
памяти, когда вы переключаетесь на другой экран
или сворачиваете приложение, чтобы экономить ресурсы 

Удаление модели: Нажмите и удерживайте модель 
в списке, выберите Delete .

3. Настройки модели (Advanced Settings)

Для доступа к настройкам нажмите на иконку шеврона
(v) рядом с моделью .

Основные параметры:

System Prompt (Системный промпт): Инструкция,
которую ИИ запоминает в начале каждого диалога.
Определяет поведение и роль ассистента

Temperature: Регулирует креативность ответов.
Высокие значения (до 2.0) дают более разнообразные
ответы, низкие (ближе к 0) — более детерминированные

n_predict: Максимальное количество токенов
для генерации ответа
Context Length (n_ctx): Размер контекста, 
который помнит модель (количество токенов)

Batch size (n_batch): Размер пакета для обработки

Use BOS/Use EOS: Включение маркеров начала
и конца последовательности

Add Generation Prompt: Включение дополнительных
подсказок для генерации

Chat Templates: Шаблоны форматирования чата
Top_k / Top_p: Параметры семплирования для выбора токенов

GPU Layers: Для Android-устройств — количество слоёв
модели, передаваемых на графический процессор
(чем выше, тем быстрее генерация) 

Область применения: Настройки сохраняются индивидуальн
о для каждой модели.

4. Pals (Персонажи)

Раздел для создания и управления персонализированными
ИИ-ассистентами с уникальными настройками .

Типы Pal:

Assistant Pal: Стандартный помощник. Можно выбрать
модель по умолчанию, настроить системный промпт
и цвет текста в чате

Roleplay Pal: Для ролевых игр. Включает дополнительные
настройки: локация, роль персонажа и 
другие контекстные параметры 

Как создать:
Перейдите в раздел Pals
Нажмите кнопку +
Выберите тип Pal (Assistant или Roleplay)
Заполните поля: название, системный промпт, модель
Сохраните

Как использовать:

Селектор Pal отображается на странице чата
Одно нажатие переключает между разными 
личностями ассистента
Можно создать системный промпт с 
помощью другого ИИ

5. Benchmarking (Тестирование производительности)

Функция для оценки производительности вашего 
устройства при работе с языковыми моделями, 
доступная в версии 1.6.2 и новее .

Как использовать:

Скачайте или выберите модель для тестирования
Перейдите на страницу Benchmark

Запустите тест со стандартными настройками:
PP (Prompt Processing) — 512 токенов,
TG (Token Generation) — 128 токенов 

Метрики производительности:

tokens per second (токенов/секунду): скорость генерации
ms per token (миллисекунд на токен): время на один токен 

Формула расчёта:

Prompt Processing (PP) — 40% веса
Token Generation (TG) — 60% веса

Результаты можно отправить в общий AI-Phone Leaderboard
для сравнения с другими устройствами 

6. Settings (Настройки)

Общие настройки приложения.

Основные параметры:

Language (Язык): Смена языка интерфейса.

Поддерживается более 13 языков, включая русский,
китайский, корейский, иврит

Экран: Функция "Screen Awake" — экран не выключается
во время генерации ответа

Управление чатами: Массовое удаление и экспорт чатов

Фоновые загрузки (iOS): Продолжение загрузки 
моделей при использовании других приложений 

7. Remote Model (Удалённые модели)

Поддержка подключения к серверам, совместимым с OpenAI API.

Поддерживаемые серверы:

LM Studio
Ollama
Другие OpenAI-совместимые серверы 

Как использовать:

Перейдите в раздел Remote Model
Введите адрес сервера и порт
При необходимости укажите API-ключ

Дополнительные функции
Hugging Face Integration
Поиск GGUF-моделей непосредственно в приложении
Закладки для избранных моделей
Поддержка как публичных, так и закрытых
(gated) моделей с аутентификацией 

Real-Time Performance Metrics

Отображение скорости генерации в реальном времени:
токенов в секунду и миллисекунд на токен .
Auto Offload/Load

Автоматическое управление памятью — модель
выгружаетсяпри работе в фоновом режиме и 
загружается при возврате в приложение .
Message Editing

Возможность редактировать свои сообщения и 
повторно генерировать ответы ИИ .
Table Rendering

Поддержка отображения таблиц в ответах в 
формате Markdown .

Vision Model Control

Для моделей с поддержкой зрения: настраиваемые
лимиты токенов для обработки изображений .

Быстрый старт: пошаговая инструкция

Установите приложение из официального магазина
Откройте меню (☰) и перейдите в Settings для
выбора языка интерфейса

Перейдите в раздел Models и скачайте модель 
(рекомендуется начать с лёгкой модели, 
например, Qwen3-0.6B-GGUF)
Нажмите Load для загрузки модели в память
Перейдите в Chat и начните общение с ИИ
При необходимости создайте Pal для быстрого
переключения между разными личностями 
ассистента

Примечание

Все операции обработки данных выполняются локально
на устройстве. Ваши разговоры, подсказки и данные 
никогда не покидают ваш телефон и не сохраняются
на внешних серверах 

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

    private fun hideKeyboard() { try { val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager; val view = currentFocus ?: View(this); imm.hideSoftInputFromWindow(view.windowToken, 0) } catch (_: Exception) {} }

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
        cloudClient.newCall(request).enqueue(object : Callback {
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

    private fun setStatus(text: String, color: String) = runOnUiThread { try { statusText.text = "$text | ${getMyAge()}"; statusDot.setBackgroundResource(when(color){"green"->R.drawable.status_dot_green;"yellow"->R.drawable.status_dot_yellow;"red"->R.drawable.status_dot_red;"blue"->R.drawable.status_dot_green;else->R.drawable.status_dot_gray}) } catch (_: Exception) {} }
    private fun appendChat(text: String) = runOnUiThread { try { chatOutput.append("\n\n$text"); scrollChatToBottom() } catch (_: Exception) {} }

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
    
    private fun captureAndAnalyze() = try { cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE)) } catch (e: Exception) { appendChat("[ERROR] ${e.message}") }
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
        cloudClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                appendChat("[АНАЛИЗ] Ошибка: ${e.message}")
                setStatus("Ошибка", "red")
            }
            override fun onResponse(call: Call, response: Response) {
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
                response.close()
            }
        })
    }

    private fun generateToken() {
        val authKey = authKeyInput.text.toString().trim()
        if (authKey.isEmpty()) return
        setStatus("Генерация...", "yellow")
        if (currentApiUrl == apiUrlGigaChat) {
            cloudClient.newCall(Request.Builder().url(authUrl).header("Content-Type","application/x-www-form-urlencoded").header("Authorization","Basic $authKey").header("RqUID","ac5edc2e-2c74-47cb-97c1-69249136cf8b").post(RequestBody.create("application/x-www-form-urlencoded".toMediaType(), "scope=GIGACHAT_API_PERS")).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { appendChat("[ERROR] ${e.message}") }
                override fun onResponse(call: Call, response: Response) { val b = response.body?.string() ?: ""; if (response.isSuccessful) { val t = gson.fromJson(b, JsonObject::class.java).get("access_token")?.asString ?: ""; if (t.isNotEmpty()) { runOnUiThread { tokenInput.setText(t) }; appendChat("[SYSTEM] Токен готов."); setStatus("Готов", "green") } } else appendChat("[ERROR] HTTP ${response.code}"); response.close() }
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
        cloudClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { appendChat("[ERROR] ${e.message}") }
            override fun onResponse(call: Call, response: Response) { appendChat(if (response.isSuccessful) "[SYSTEM] Токен активен." else "[ERROR] Токен не работает."); response.close() }
        })
    }
    
    private fun sendMessage() {
        val token = tokenInput.text.toString().trim()
        val msg = messageInput.text.toString().trim()

        // ===== ПРОВЕРКА ЛОКАЛЬНОЙ МОДЕЛИ =====
        if (LlamaJNI.isModelLoaded()) {
            if (msg.isEmpty()) { appendChat("[SYSTEM] Введите сообщение."); return }
            if (msg.lowercase().trim() == "help") { showHelpDialog(); messageInput.setText(""); hideKeyboard(); return }
            if (msg.lowercase().contains(rememberCommand)) { analyzeAndRemember(); messageInput.setText(""); hideKeyboard(); return }
            sendToLocal(msg)
            return
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

        cloudClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                appendChat("[ERROR] ${e.message}")
                matrixHeader.connectionLost = true
                setStatus("Нет связи", "red")
            }
            override fun onResponse(call: Call, response: Response) {
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
                response.close()
            }
        })
    }

    private fun speakText(text: String) {
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
    }
}
