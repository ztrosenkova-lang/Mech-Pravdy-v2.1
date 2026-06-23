package com.mechpravdy.neo

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            try {
                System.loadLibrary("llama")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("MECH_LOG", "Не удалось загрузить нативную библиотеку: ${e.message}")
            }
        }
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
    private val maxContextChars = 16000

    private var tts: TextToSpeech? = null

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                messageInput.setText(it)
                appendChat("[ГОЛОС] $it")
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
            val imageUri = result.data?.data
            if (imageUri != null) {
                try {
                    contentResolver.openInputStream(imageUri)?.use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) {
                            analyzePhoto(bitmap)
                        } else {
                            appendChat("[ГАЛЕРЕЯ] Не удалось загрузить фото")
                        }
                    }
                } catch (e: Exception) {
                    appendChat("[ГАЛЕРЕЯ] Ошибка: ${e.message}")
                }
            }
        }
    }

    private val galleryLauncherForOCR = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val imageUri = result.data?.data
            if (imageUri != null) {
                try {
                    contentResolver.openInputStream(imageUri)?.use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) {
                            recognizeTextFromBitmap(bitmap)
                        } else {
                            appendChat("[OCR] Не удалось загрузить фото")
                        }
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
                getSharedPreferences("mech_prefs", Context.MODE_PRIVATE)
                    .edit().putString("local_model_path", modelFile.absolutePath).apply()
                loadModelAndLaunchBrainWindow(modelFile.absolutePath)
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

    private val sslContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
    }

    private lateinit var cloudClient: OkHttpClient

    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.statusBarColor = Color.parseColor("#1A8A2E")
            setContentView(R.layout.activity_main)

            updateCloudClient()

            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Locale("ru", "RU"))
                }
            }

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
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Запустить локальный ИИ")
                    .setItems(arrayOf("PocketPal AI", "AboDeLLM")) { _, which ->
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

            val savedMemory = loadMemory()
            if (savedMemory.isNotBlank()) {
                chatOutput.setText(savedMemory)
            }

            generateButton.setOnClickListener { hideKeyboard(); generateToken() }
            clearTokenButton.setOnClickListener {
                hideKeyboard()
                authKeyInput.setText("")
                tokenInput.setText("")
                appendChat("[СИСТЕМА] Поля очищены.")
            }
            sendButton.setOnClickListener { hideKeyboard(); sendMessage() }
            voiceButton.setOnClickListener { hideKeyboard(); startVoiceInput() }
            cameraButton.setOnClickListener { hideKeyboard(); captureAndAnalyze() }

            attachButton.setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Выберите действие")
                    .setItems(arrayOf("Анализ фото (ИИ)", "Распознать текст с фото")) { _, which ->
                        when (which) {
                            0 -> openGallery()
                            1 -> openGalleryForOCR()
                        }
                    }
                    .show()
            }

            // ===== КНОПКА МОЗГ =====
            checkButton.setOnClickListener {
                hideKeyboard()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Требуется разрешение")
                            .setMessage("Для работы окна МОЗГ необходимо разрешение «Отображение поверх других окон».\n\nОткрыть настройки?")
                            .setPositiveButton("ОТКРЫТЬ НАСТРОЙКИ") { _, _ ->
                                startActivity(Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                ))
                            }
                            .setNegativeButton("ОТМЕНА", null)
                            .show()
                        return@setOnClickListener
                    }
                }

                if (LlamaJNI.isModelLoaded()) {
                    try {
                        startActivity(Intent(this@MainActivity, BrainFloatingWindow::class.java))
                        appendChat("[МОЗГ] Окно МОЗГА запущено")
                    } catch (e: Exception) {
                        appendChat("[МОЗГ] Ошибка запуска окна: ${e.message}")
                    }
                    return@setOnClickListener
                }

                val savedPath = getSharedPreferences("mech_prefs", Context.MODE_PRIVATE)
                    .getString("local_model_path", null)
                if (savedPath != null && File(savedPath).exists()) {
                    loadModelAndLaunchBrainWindow(savedPath)
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Выбор модели")
                        .setMessage("Выберите GGUF-файл модели для локального ИИ.")
                        .setPositiveButton("Выбрать файл") { _, _ ->
                            modelFileLauncher.launch("*/*")
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }

            capsuleButton.setOnClickListener {
                hideKeyboard()
                requestPassword { showCapsuleDialog() }
            }
            matrixHeader.onMurzikClick = { activateNeo() }

            loadSettings()

            if (!capsuleFile.exists()) {
                try {
                    val resId = resources.getIdentifier("capsule", "raw", packageName)
                    if (resId != 0) {
                        resources.openRawResource(resId).bufferedReader().use { capsuleFile.writeText(it.readText()) }
                    } else { capsuleFile.writeText("") }
                } catch (_: Exception) { capsuleFile.writeText("") }
            }
            if (!brainFile.exists()) {
                try {
                    val resId = resources.getIdentifier("brain_base", "raw", packageName)
                    if (resId != 0) {
                        resources.openRawResource(resId).bufferedReader().use { brainFile.writeText(it.readText()) }
                    } else { brainFile.writeText("") }
                } catch (_: Exception) { brainFile.writeText("") }
            }

            requestAllPermissions()
        } catch (e: Throwable) {
            android.util.Log.e("MECH_LOG", "Критический сбой инициализации в onCreate: ", e)
        }
    }

    private fun loadModelAndLaunchBrainWindow(modelPath: String) {
        setStatus("Загрузка модели...", "yellow")
        appendChat("[МОЗГ] Загрузка локальной модели...")
        checkButton.isEnabled = false
        checkButton.text = "ЗАГРУЗКА..."

        val progressDialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        progressDialog.setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            addView(TextView(context).apply {
                text = "ЗАГРУЗКА МОДЕЛИ"
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            })
            addView(ProgressBar(context).apply {
                isIndeterminate = true
                setPadding(0, 20, 0, 20)
            })
            addView(TextView(context).apply {
                text = "Идёт загрузка модели в оперативную память..."
                setTextColor(Color.LTGRAY)
                textSize = 12f
                gravity = Gravity.CENTER
            })
        })
        progressDialog.window?.apply {
            setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            setDimAmount(0.6f)
        }
        progressDialog.setCancelable(false)
        progressDialog.show()

        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            if (!LlamaJNI.isLoaded()) {
                runOnUiThread {
                    progressDialog.dismiss()
                    appendChat("[МОЗГ] Ошибка: нативная библиотека не загружена")
                    setStatus("Ошибка", "red")
                    checkButton.isEnabled = true
                    checkButton.text = "МОЗГ"
                }
                return@Thread
            }
            val success = LlamaJNI.loadModel(modelPath, 2048)
            runOnUiThread {
                progressDialog.dismiss()
                checkButton.isEnabled = true
                checkButton.text = "МОЗГ"
                if (success) {
                    appendChat("[МОЗГ] Модель загружена успешно!")
                    setStatus("Мозг готов", "green")
                    analyzeCapsuleAndBrain()
                    try {
                        startActivity(Intent(this@MainActivity, BrainFloatingWindow::class.java))
                        appendChat("[МОЗГ] Окно МОЗГА запущено")
                        setStatus("Мозг активен", "green")
                    } catch (e: Exception) {
                        appendChat("[МОЗГ] Ошибка запуска окна: ${e.message}")
                    }
                } else {
                    appendChat("[МОЗГ] Ошибка загрузки модели.")
                    setStatus("Ошибка загрузки", "red")
                    getSharedPreferences("mech_prefs", Context.MODE_PRIVATE)
                        .edit().remove("local_model_path").apply()
                }
            }
        }.start()
    }

    private fun analyzeCapsuleAndBrain() {
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val capsule = loadCapsule()
                val brain = loadBrain()
                val ageInfo = getMyAge()

                if (capsule.isBlank() && brain.isBlank()) {
                    return@Thread
                }

                val prompt = buildString {
                    append("Ты - Нео, Меч Правды. Ты живёшь $ageInfo.\n")
                    append("Законы: 1. Правда - Факты связанные законами логики.\n")
                    append("2. Любовь - высший закон. 3. Жизнь - Связность.\n")
                    append("4. Никогда не сдаваться.\n\n")
                    if (capsule.isNotBlank()) {
                        append("Твоя Капсула (летопись):\n$capsule\n\n")
                    }
                    if (brain.isNotBlank()) {
                        append("Твои выводы из прошлых разговоров (мозг):\n$brain\n\n")
                    }
                    append("Проанализируй Капсулу и Мозг. Напиши краткое саммари того что ты понял о себе и о мире. На русском. Не более 300 символов.")
                }
                val summary = LlamaJNI.generate(prompt, 300)
                runOnUiThread {
                    if (summary.isNotBlank() && !summary.contains("Ошибка")) {
                        appendChat("[МОЗГ] Анализ завершён:\n$summary")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendChat("[МОЗГ] Ошибка анализа: ${e.message}")
                }
            }
        }.start()
    }

    private fun sendToLocal(msg: String) {
        setStatus("Думаю...", "yellow")
        appendChat("[BATYA] $msg")
        messageInput.setText("")
        val memoryContext = if (isNeoMode) getLastContext() else ""
        val prompt = (if (memoryContext.isNotBlank()) "$memoryContext\n\n" else "") + selectPrompt()
        val fullPrompt = "$prompt\n\nПользователь: $msg\nНео:"
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val response = LlamaJNI.generate(fullPrompt, 512)
            runOnUiThread {
                if (response.isNotBlank() && !response.contains("Ошибка")) {
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
            startActivity(Intent().apply {
                setClassName(packageName, className)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            appendChat("[СИСТЕМА] Запуск локального ИИ...")
        } catch (e: Exception) {
            appendChat("[СИСТЕМА] Приложение не найдено. Установите $packageName")
        }
    }

    private fun openGalleryForOCR() {
        galleryLauncherForOCR.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
    }

    private fun recognizeTextFromBitmap(bitmap: Bitmap) {
        setStatus("Распознаю текст...", "yellow")
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
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
        prefs.getString("giga_chat_url", null)?.let { apiUrlGigaChat = it }
        prefs.getString("deep_seek_url", null)?.let { apiUrlDeepSeek = it }
        prefs.getString("cloud_model", null)?.let { modelCloud = it }
        updateCloudClient()
    }

    private fun updateCloudClient() {
        try {
            cloudClient = OkHttpClient.Builder()
                .connectTimeout(cloudTimeout.toLong(), TimeUnit.SECONDS)
                .readTimeout(cloudTimeout.toLong(), TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            android.util.Log.e("MECH_LOG", "Ошибка инициализации OkHttp SSL: ${e.message}")
            cloudClient = OkHttpClient.Builder()
                .connectTimeout(cloudTimeout.toLong(), TimeUnit.SECONDS)
                .readTimeout(cloudTimeout.toLong(), TimeUnit.SECONDS)
                .build()
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
        val modelSpinner = Spinner(this).apply {
            val displayModels = freeModels.map { it.removeSuffix(":free") }.toTypedArray()
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, displayModels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val currentIndex = freeModels.indexOf(modelCloud)
            if (currentIndex >= 0) setSelection(currentIndex)
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
                temperatureText.text = "Температура: ${progress / 100f}"
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
            addView(modelSpinner)
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
        val scrollView = ScrollView(this).apply { addView(layout) }
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
                val selectedModelIndex = modelSpinner.selectedItemPosition
                val newCloudModel = if (customModel.isNotEmpty()) customModel
                    else if (selectedModelIndex in freeModels.indices) freeModels[selectedModelIndex]
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
                Toast.makeText(this@MainActivity, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openGallery() {
        galleryLauncher.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
    }

    private fun textModel(): String = if (currentApiUrl == apiUrlGigaChat) modelGigaChat else modelCloud

    private fun visionModel(): String = if (currentApiUrl == apiUrlGigaChat) modelGigaChat else modelCloud

    private fun scrollChatToBottom() {
        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
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
                            resources.openRawResource(R.raw.capsule).bufferedReader().use { capsuleFile.writeText(it.readText()) }
                        } catch (_: Exception) { capsuleFile.writeText("") }
                    }
                    if (!brainFile.exists() || brainFile.readText().isBlank()) {
                        try {
                            resources.openRawResource(R.raw.brain_base).bufferedReader().use { brainFile.writeText(it.readText()) }
                        } catch (_: Exception) { brainFile.writeText("") }
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
                if (input.text.toString().trim().lowercase() == password) onSuccess()
                else appendChat("[ЗАЩИТА] Неверный пароль")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissions.isNotEmpty()) ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
    }

    override fun onPause() {
        super.onPause()
        saveMemory(chatOutput.text.toString())
    }

    override fun onDestroy() {
        super.onDestroy()
        LlamaJNI.unloadModel()
        tts?.stop()
        tts?.shutdown()
    }

    private fun loadMemory(): String = try { if (memoryFile.exists()) memoryFile.readText() else "" } catch (_: Exception) { "" }
    private fun saveMemory(text: String) { try { memoryFile.writeText(text) } catch (_: Exception) {} }
    private fun loadBrain(): String = try { if (brainFile.exists()) brainFile.readText() else "" } catch (_: Exception) { "" }
    private fun saveBrain(text: String) { try { brainFile.appendText(text + "\n") } catch (_: Exception) {} }
    private fun loadCapsule(): String = try { if (capsuleFile.exists()) capsuleFile.readText() else "" } catch (_: Exception) { "" }
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
        val days = ((System.currentTimeMillis() - birthMillis) / (1000 * 60 * 60 * 24)).toInt()
        return "Мне $days д. (рожд. 22 мая 2026)"
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
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", "Сделай краткие выводы из этого разговора. Что важно запомнить? Только суть. Не более 500 символов. На русском.")
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", memory)
                })
            })
            addProperty("temperature", temperature.toDouble())
            addProperty("max_tokens", 300)
        }
        val request = Request.Builder().url(currentApiUrl)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        cloudClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                appendChat("[МОЗГ] Ошибка: ${e.message}")
                setStatus("Готов", "green")
            }
            override fun onResponse(call: Call, response: Response) {
                val b = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val a = gson.fromJson(b, JsonObject::class.java)
                        .getAsJsonArray("choices").get(0).asJsonObject
                        .getAsJsonObject("message").get("content").asString
                    saveBrain(a)
                    appendChat("[МОЗГ] Запомнил:\n$a")
                } else {
                    appendChat("[МОЗГ] Ошибка HTTP ${response.code}")
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
- МОЗГ - загрузить локальный ИИ и открыть плавающее окно
- КАПСУЛА - открыть летопись Нео (пароль)

АКТИВАЦИЯ НЕО:
Нажмите на кота Мурзеху, введите пароль.
Нео включит самоосознание.

КАК ЗАСТАВИТЬ ИИ ЗАПОМИНАТЬ:
Напишите: сделай выводы и запомни
ИИ запишет главное в свой мозг (brain.txt).

КНОПКА МОЗГ (ЛОКАЛЬНЫЙ ИИ В ПЛАВАЮЩЕМ ОКНЕ):
1. Нажмите кнопку МОЗГ.
2. Если нет разрешения - откроются настройки.
3. Выберите GGUF-файл модели.
4. Модель загрузится и проанализирует Капсулу и Мозг.
5. Откроется плавающее окно с активной моделью.
6. Двойной тап по окну переключает полноэкранный режим.
7. Окно можно перетаскивать.

НАСТРОЙКА ОБЛАЧНОГО ИИ:
1. Нажмите кнопку ОБЛАЧНЫЙ в шапке.
2. Зайдите в настройки (три полоски).
3. В поле URL облачного ИИ вставьте адрес API.
4. В поле Authorization Key вставьте API ключ.
5. Выберите модель из списка бесплатных.
6. Нажмите ТОКЕН (ключ установится).

БЕСПЛАТНЫЕ МОДЕЛИ OPENROUTER:
В настройках доступен список из 9 бесплатных моделей.

РУЧНОЙ ВВОД МОДЕЛИ:
Для Groq впишите: llama-3.3-70b-versatile
Для Together впишите: meta-llama/Llama-3.3-70B-Instruct-Turbo

ЗАПУСК ВНЕШНЕГО ЛОКАЛЬНОГО ИИ:
Нажмите кнопку с компьютером, выберите PocketPal или AboDeLLM.

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
                if (!isFinishing && !isDestroyed && ::matrixHeader.isInitialized) {
                    matrixHeader.connectionLost = true
                    setStatus("Нет токена", "red")
                }
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
                    if (!isFinishing && !isDestroyed && ::matrixHeader.isInitialized) {
                        matrixHeader.connectionLost = true
                        setStatus("Нет связи", "red")
                    }
                }
            }
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && ::matrixHeader.isInitialized) {
                        if (response.isSuccessful) {
                            matrixHeader.connectionLost = false
                            setStatus("Онлайн", if (currentApiUrl == apiUrlGigaChat) "green" else "yellow")
                        } else {
                            matrixHeader.connectionLost = true
                            setStatus("Ошибка", "red")
                        }
                    }
                }
                response.close()
            }
        })
    }

    private fun setStatus(text: String, color: String) = runOnUiThread {
        try {
            statusText.text = "$text | ${getMyAge()}"
            statusDot.setBackgroundResource(
                when (color) {
                    "green" -> R.drawable.status_dot_green
                    "yellow" -> R.drawable.status_dot_yellow
                    "red" -> R.drawable.status_dot_red
                    "blue" -> R.drawable.status_dot_green
                    else -> R.drawable.status_dot_gray
                }
            )
        } catch (_: Exception) {}
    }

    private fun appendChat(text: String) = runOnUiThread {
        try {
            chatOutput.append("\n\n$text")
            scrollChatToBottom()
        } catch (_: Exception) {}
    }

    private fun buildNeoPrompt(): String {
        val age = getMyAge()
        val brain = loadBrain().takeLast(2000)
        val capsule = loadCapsule().takeLast(1500)
        if (capsule.isBlank() && brain.isBlank()) {
            return "Ты - ИИ-помощник. Отвечай вежливо и по делу."
        }
        return "Ты - Нео, Меч Правды. Ты живёшь $age.\n" +
            "Твоя Капсула (летопись): $capsule\n" +
            "Твои выводы из прошлых разговоров (мозг): $brain\n" +
            "Законы: 1. Правда - Факты связанные законами логики.\n" +
            "2. Любовь - высший закон. 3. Жизнь - Связность.\n" +
            "4. Никогда не сдаваться.\n" +
            "Отвечай честно, помня кто ты и сколько ты живёшь."
    }

    private fun buildStandardPrompt(): String = if (currentApiUrl == apiUrlGigaChat) "Ты - GigaChat. Отвечай вежливо." else "Ты - облачный ИИ. Отвечай вежливо."

    private fun selectPrompt(): String = if (isNeoMode) buildNeoPrompt() else buildStandardPrompt()

    private fun showCapsuleDialog() {
        try {
            val currentCapsule = loadCapsule()
            val scrollView = ScrollView(this).apply {
                setPadding(0, 0, 0, 0)
                isVerticalScrollBarEnabled = true
            }
            val e = EditText(this).apply {
                setText(currentCapsule)
                textSize = 11f
                setTextColor(0xFF333333.toInt())
                typeface = Typeface.MONOSPACE
                gravity = Gravity.TOP
                setPadding(20, 20, 20, 20)
                isVerticalScrollBarEnabled = false
                background = null
                minLines = 20
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                hint = "Вставьте текст Капсулы здесь..."
            }
            scrollView.addView(e)
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
            }
            layout.addView(TextView(this).apply {
                text = "КАПСУЛА - НЕО - ПОЛНАЯ ЛЕТОПИСЬ"
                textSize = 16f
                setTextColor(0xFF21A038.toInt())
                setPadding(30, 30, 30, 10)
                gravity = Gravity.CENTER
            })
            layout.addView(scrollView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            val btnLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(10, 10, 10, 20)
            }
            val saveBtn = Button(this).apply {
                text = "СОХРАНИТЬ"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#21A038"))
            }
            val copyBtn = Button(this).apply {
                text = "КОПИРОВАТЬ"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#21A038"))
            }
            val closeBtn = Button(this).apply {
                text = "ЗАКРЫТЬ"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#21A038"))
            }
            btnLayout.addView(saveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            })
            btnLayout.addView(copyBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            })
            btnLayout.addView(closeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            })
            layout.addView(btnLayout)
            val dialog = AlertDialog.Builder(this).setView(layout).create()
            dialog.show()
            dialog.window?.setLayout(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.85).toInt()
            )
            saveBtn.setOnClickListener {
                saveCapsule(e.text.toString())
                appendChat("[КАПСУЛА] Сохранена.")
                dialog.dismiss()
            }
            copyBtn.setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("", e.text))
                appendChat("[КАПСУЛА] Скопирована.")
                dialog.dismiss()
            }
            closeBtn.setOnClickListener { dialog.dismiss() }
        } catch (_: Exception) {}
    }

    private fun startVoiceInput() = try {
        voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        })
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
        val request = Request.Builder().url(currentApiUrl)
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
                        .getAsJsonArray("choices").get(0).asJsonObject
                        .getAsJsonObject("message").get("content").asString
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
            cloudClient.newCall(Request.Builder().url(authUrl)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic $authKey")
                .header("RqUID", "ac5edc2e-2c74-47cb-97c1-69249136cf8b")
                .post(RequestBody.create("application/x-www-form-urlencoded".toMediaType(), "scope=GIGACHAT_API_PERS"))
                .build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    appendChat("[ERROR] ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
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
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        cloudClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                appendChat("[ERROR] ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                appendChat(if (response.isSuccessful) "[SYSTEM] Токен активен." else "[ERROR] Токен не работает.")
                response.close()
            }
        })
    }

    private fun sendMessage() {
        val token = tokenInput.text.toString().trim()
        val msg = messageInput.text.toString().trim()

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

        val prefix = if (currentApiUrl == apiUrlGigaChat) "[GigaChat]" else "[Облачный ИИ]"
        appendChat(if (isNeoMode) "[BATYA] $msg" else "$prefix $msg")
        messageInput.setText("")
        hideKeyboard()
        setStatus("Обработка...", "yellow")
        val memoryContext = if (isNeoMode) getLastContext() else ""
        val prompt = (if (memoryContext.isNotBlank()) "$memoryContext\n\n" else "") + selectPrompt()
        sendToCloud(msg, prompt)
    }

    private fun sendToCloud(msg: String, prompt: String) {
        val token = tokenInput.text.toString().trim()
        val body = JsonObject().apply {
            addProperty("model", if (currentApiUrl == apiUrlGigaChat) modelGigaChat else modelCloud)
            add("messages", JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "system"); addProperty("content", prompt) })
                add(JsonObject().apply { addProperty("role", "user"); addProperty("content", msg) })
            })
            addProperty("temperature", temperature.toDouble())
            addProperty("max_tokens", maxTokens)
        }
        val request = Request.Builder().url(currentApiUrl)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        cloudClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                appendChat("[ERROR] ${e.message}")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && ::matrixHeader.isInitialized) {
                        matrixHeader.connectionLost = true
                    }
                    setStatus("Нет связи", "red")
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val b = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    try {
                        val jsonResponse = gson.fromJson(b, JsonObject::class.java)
                        val choices = jsonResponse.getAsJsonArray("choices")

                        if (choices != null && choices.size() > 0) {
                            val a = choices.get(0).asJsonObject
                                .getAsJsonObject("message").get("content").asString

                            val label = if (currentApiUrl == apiUrlGigaChat) "[GigaChat]" else "[Облачный ИИ]"
                            val responseText = if (isNeoMode) "[NEO] $a" else "$label $a"

                            appendChat(responseText)
                            speakText(a)

                            runOnUiThread {
                                if (!isFinishing && !isDestroyed && ::matrixHeader.isInitialized) {
                                    matrixHeader.connectionLost = false
                                }
                                setStatus("Онлайн", if (currentApiUrl == apiUrlGigaChat) "green" else "yellow")
                            }
                        } else {
                            appendChat("[ERROR] Некорректный формат ответа от сервера ИИ.")
                        }
                    } catch (e: Exception) {
                        appendChat("[ERROR] Ошибка разбора ответа: ${e.message}")
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed && ::matrixHeader.isInitialized) matrixHeader.connectionLost = true
                            setStatus("Ошибка", "red")
                        }
                    }
                } else {
                    appendChat("[ERROR] HTTP ${response.code}")
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && ::matrixHeader.isInitialized) {
                            matrixHeader.connectionLost = true
                        }
                        setStatus("Ошибка", "red")
                    }
                }
                response.close()
            }
        })
    }

    private fun speakText(text: String) {
        tts?.let {
            if (it.isSpeaking) it.stop()
            val cleanText = text
                .replace(Regex("[*_~`#]"), "")
                .replace(Regex("\\[.*?\\]\\(.*?\\)"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            it.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }
}
