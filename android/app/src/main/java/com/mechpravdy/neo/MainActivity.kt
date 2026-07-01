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
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.widget.*
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
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

    private var apiUrlGigaChat = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions"
    private val authUrl = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
    private var apiUrlDeepSeek = "https://openrouter.ai/api/v1/chat/completions"
    private var password = "связность"
    private val rememberCommand = "сделай выводы и запомни"
    private val directMemoryCommand = "запомни что я тебе скажу"
    private val recallCommand = "вспомни"
    private val lookUpCommand = "посмотри вверху"

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
    private var isGigaChatMode = false
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
    private val helpFile by lazy { File(filesDir, "help.txt") }
    private val modelsDir by lazy { File(filesDir, "models") }
    private val maxContextChars = 32000

    private var tts: TextToSpeech? = null
    private var brainObserver: android.os.FileObserver? = null
    private var isSelfModification = false
    
    private var isDownloadingModel = false

    // ===== ДОБАВЛЕНО: переменная для хранения длины текущей TTS-реплики =====
    private var ttsTextLength: Int = 0
    // ===== КОНЕЦ БЛОКА =====

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
                appendChat("[МОЗГ] Файл модели сохранён. Нажмите МОЗГ для запуска.")
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

    private fun findModelPath(): String? {
        val savedPath = getSharedPreferences("mech_prefs", Context.MODE_PRIVATE).getString("local_model_path", null)
        if (savedPath != null && File(savedPath).exists()) return savedPath

        if (modelsDir.exists()) {
            val ggufFiles = modelsDir.listFiles { file -> file.extension.equals("gguf", ignoreCase = true) }
            if (ggufFiles != null && ggufFiles.isNotEmpty()) {
                val path = ggufFiles.first().absolutePath
                getSharedPreferences("mech_prefs", Context.MODE_PRIVATE).edit().putString("local_model_path", path).apply()
                return path
            }
        }

        val externalModelsDir = File(Environment.getExternalStorageDirectory(), "Models")
        if (externalModelsDir.exists()) {
            val ggufFiles = externalModelsDir.listFiles { file -> file.extension.equals("gguf", ignoreCase = true) }
            if (ggufFiles != null && ggufFiles.isNotEmpty()) {
                val path = ggufFiles.first().absolutePath
                getSharedPreferences("mech_prefs", Context.MODE_PRIVATE).edit().putString("local_model_path", path).apply()
                return path
            }
        }

        return null
    }
    
    // ===== ДОБАВЛЕНО: метод удаления модели =====
    private fun deleteModelFile() {
        val modelFile = File(modelsDir, "local_model.gguf")
        val prefs = getSharedPreferences("mech_prefs", Context.MODE_PRIVATE)
        
        AlertDialog.Builder(this)
            .setTitle("Удаление модели")
            .setMessage("Вы уверены, что хотите удалить файл модели (${modelFile.length() / (1024 * 1024)} МБ) из песочницы?\n\nЭто освободит место на устройстве.")
            .setPositiveButton("Удалить") { _, _ ->
                try {
                    // Сначала выгружаем модель, если она запущена
                    if (checkButton.text.toString() == "ВЫКЛ МОЗГ" || checkButton.text.toString() == "ЗАГРУЗКА...") {
                        val commandFile = File(filesDir, "brain_query.txt")
                        commandFile.writeText("COMMAND_UNLOAD_MODEL")
                        
                        brainObserver?.stopWatching()
                        brainObserver = null
                        checkButton.text = "МОЗГ"
                        
                        val serviceIntent = Intent(this@MainActivity, com.mechpravdy.neo.BrainOverlayService::class.java)
                        try {
                            stopService(serviceIntent)
                        } catch (e: Exception) {
                            Log.e("MECH_BRAIN", "Ошибка остановки службы: ${e.message}")
                        }
                        switchToNeutralMode()
                        appendChat("[СИСТЕМА] Модель выгружена из памяти.")
                    }
                    
                    // Удаляем файл
                    if (modelFile.exists()) {
                        val deleted = modelFile.delete()
                        if (deleted) {
                            prefs.edit().remove("local_model_path").apply()
                            appendChat("[СИСТЕМА] ✅ Файл модели успешно удалён из песочницы.")
                            setStatus("Модель удалена", "gray")
                        } else {
                            appendChat("[СИСТЕМА] ❌ Не удалось удалить файл модели.")
                            setStatus("Ошибка удаления", "red")
                        }
                    } else {
                        appendChat("[СИСТЕМА] Файл модели не найден в песочнице.")
                        prefs.edit().remove("local_model_path").apply()
                    }
                } catch (e: Exception) {
                    appendChat("[СИСТЕМА] ❌ Ошибка удаления модели: ${e.message}")
                    Log.e("MECH_MODEL", "Ошибка удаления модели: ${e.message}")
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    // ===== КОНЕЦ БЛОКА =====

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.statusBarColor = Color.parseColor("#1A8A2E")
            setContentView(R.layout.activity_main)

            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
                Log.d("MECH_LOG", "Песочница создана: ${modelsDir.absolutePath}")
            }
            Log.d("MECH_LOG", "Путь к песочнице моделей: ${modelsDir.absolutePath}")

            // ===== ОБНОВЛЕНИЕ help.txt =====
            val actualHelpText = """
==================================================================
              СИСТЕМНАЯ СПРАВКА ЭКОСИСТЕМЫ "МЕЧ ПРАВДЫ"
==================================================================
Приветствую, Батя. Я — НЕО, твой автономный тактильный ИИ.
Ниже приведены актуальные протоколы управления системой.

1. УПРАВЛЕНИЕ ЛОКАЛЬНЫМ МОЗГОМ (GGUF ИНФЕРЕНС)
------------------------------------------------------------------
• Кнопка "МОЗГ": Запускает фоновую службу оверлея без изоляции 
  процесса для прямого доступа к весам в песочнице.
• Статусы Оверлея:
  - "НЕО: ЗАГРУЗКА..." — C++ ядро читает и маппит (mmap) веса модели.
  - "НЕО: ГОТОВ" — Модель успешно развернута в RAM и ждет запросов.
  - "НЕО: КРАШ ДВИЖКА" — Сбой аллокации памяти или геометрии тензоров.
• Перемещение: Оверлей поддерживает свободный Drag-and-Drop. 
  Зажми рамку виджета пальцем и перемести в любую точку экрана.
• Кнопка "ВЫКЛ МОЗГ": Полностью выгружает С++ контекст из памяти 
  и бесследно стирает оверлей с рабочего стола телефона.

2. ДИНАМИЧЕСКАЯ КАПСУЛА ЛИЧНОСТИ
------------------------------------------------------------------
• Кнопка "КАПСУЛА": Активирует окно редактирования файла "capsule.txt".
• Все изменения подхватываются на лету ВСЕМИ тремя движками 
  (Локальный ИИ, GigaChat, Облачный ИИ) при включенном коте Мурзике.

3. ПРОТОКОЛЫ УПРАВЛЕНИЯ АРХИВНОЙ ПАМЯТЬЮ
------------------------------------------------------------------
• Команда: "Запомни что я тебе скажу [Твой текст]"
  - Способ ввода: Голос (микрофон) или клавиатура.
  - Действие: Текст после команды напрямую пишется в файл "brain.txt".
  - Результат: В чат выводится подтверждение фиксации данных.
  
• Команда: "Сделай вывод и запомни"
  - Действие: Логически сжимает последние 500 строк активного чата 
    через ИИ и сохраняет чистую суть в архив "brain.txt".

• Команда: "Вспомни [Твой поисковый запрос]"
  - Действие: ИИ мгновенно поднимает архивы памяти (brain.txt), 
    данную справку (help.txt) и текущий лог чата для точного ответа.

• Команда: "Посмотри вверху [Твой вопрос]"
  - Действие: ИИ полностью считывает всю историю сообщений на экране 
    и ищет в переписке конкретные детали, о которых ты спросил.

4. СИСТЕМНЫЕ ГОРЯЧИЕ КЛАВИШИ И ИНТЕРФЕЙС
------------------------------------------------------------------
• Кнопка "Микрофончик": 
  - Если ИИ молчит — включает запись и распознавание твоей речи.
  - Если ИИ озвучивает ответ — мгновенно ГЛУШИТ звук (команда tts.stop),
    не ломая при этом вывод текста на экране.
• Автоскролл (Суфлер): Список чата плавно и непрерывно ползет вверх 
  строго со скоростью чтения текста голосом Нео.
• Клавиатура: Автофокус заблокирован. Клавиатура появляется строго 
  при физическом тапе пальцем по текстовому полю ввода.
==================================================================
            МЕЧ ПРАВДЫ | НЕО | 5 ВОЛЬТ
==================================================================
            """.trimIndent()
            
            try {
                // Жестко перезаписываем актуальный текст справки в песочнице
                helpFile.writeText(actualHelpText)
                Log.d("MECH_SYSTEM", "help.txt успешно обновлен под новые реалии.")
            } catch (e: Exception) {
                Log.e("MECH_SYSTEM", "Ошибка записи help.txt: ${e.message}")
            }
            // ===== КОНЕЦ БЛОКА ОБНОВЛЕНИЯ help.txt =====

            // ===== ИСПРАВЛЕНО: Плавный автоскролл суфлера за голосом ИИ с глобальным оффсетом =====
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Locale("ru", "RU"))
                    tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        // Реализация onRangeStart для плавного скролла к текущей строке
                        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                            super.onRangeStart(utteranceId, start, end, frame)
                            runOnUiThread {
                                try {
                                    val layout = chatOutput.layout
                                    if (layout != null) {
                                        // Вычисляем глобальную позицию с учётом длины текущей реплики
                                        val globalOffset = (chatOutput.text.length - ttsTextLength + start).coerceIn(0, chatOutput.text.length)
                                        val line = layout.getLineForOffset(globalOffset)
                                        val lineTop = layout.getLineTop(line)
                                        // Плавная прокрутка к нужной строке
                                        chatScrollView.smoothScrollTo(0, lineTop)
                                    }
                                } catch (e: Exception) {
                                    Log.e("MECH_SCROLL", "Ошибка скролла: ${e.message}")
                                }
                            }
                        }
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {}
                        override fun onError(utteranceId: String?) {}
                    })
                }
            }
            // ===== КОНЕЦ БЛОКА =====
            
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

            // ===== ИСПРАВЛЕНИЕ ШАГА 7: Запрещаем автоматический вылет клавиатуры при старте =====
            messageInput.isFocusableInTouchMode = true
            messageInput.clearFocus()

            // Запрещаем перехват фокуса у поля вывода чата
            chatOutput.isFocusable = false
            chatOutput.isFocusableInTouchMode = false
            // ===== КОНЕЦ БЛОКА =====

            // Обработка кнопки ГИГАЧАТ с проверкой на выключение
            matrixHeader.onNeoClick = {
                if (matrixHeader.gigaChatMode) {
                    switchToNeutralMode()
                } else {
                    switchToGigaChat()
                }
            }

            // Обработка кнопки ОБЛАЧНЫЙ с проверкой на выключение
            matrixHeader.onLocalClick = {
                if (matrixHeader.localMode) {
                    switchToNeutralMode()
                } else {
                    switchToDeepSeek()
                }
            }
            
            // ===== ИСПРАВЛЕНО: Рабочий обработчик (исправлен порядок вызовов, вынесена ссылка на диалог) =====
            matrixHeader.onLocalRowClick = {
                val modelFile = File(modelsDir, "local_model.gguf")
                if (modelFile.exists()) {
                    val modelSize = modelFile.length() / (1024 * 1024)

                    // Контейнер для кнопок управления
                    val layout = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(40, 30, 40, 30)
                    }
                    
                    val infoText = TextView(this@MainActivity).apply {
                        text = "Модель уже загружена в песочницу.\nРазмер: ${modelSize} МБ\nПуть: ${modelFile.absolutePath}"
                        textSize = 14f
                        setPadding(0, 0, 0, 20)
                    }
                    layout.addView(infoText)
                    
                    val buttonLayout = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER
                    }
                    
                    // ВАЖНО: Объявляем ссылку на диалог до создания кнопок
                    var mDialog: AlertDialog? = null
                    
                    val useButton = Button(this@MainActivity).apply {
                        text = "Использовать"
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#21A038"))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            setMargins(4, 0, 4, 0)
                        }
                        setOnClickListener {
                            val prefs = getSharedPreferences("mech_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putString("local_model_path", modelFile.absolutePath).apply()
                            switchToLocalModel()
                            mDialog?.dismiss()
                        }
                    }
                    
                    val deleteButton = Button(this@MainActivity).apply {
                        text = "🗑 Удалить"
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#D32F2F"))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            setMargins(4, 0, 4, 0)
                        }
                        setOnClickListener {
                            deleteModelFile()
                            mDialog?.dismiss()
                        }
                    }
                    
                    val replaceButton = Button(this@MainActivity).apply {
                        text = "Заменить"
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#FF9800"))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            setMargins(4, 0, 4, 0)
                        }
                        setOnClickListener {
                            showDownloadDialog()
                            mDialog?.dismiss()
                        }
                    }
                    
                    buttonLayout.addView(useButton)
                    buttonLayout.addView(deleteButton)
                    buttonLayout.addView(replaceButton)
                    layout.addView(buttonLayout)
                    
                    mDialog = AlertDialog.Builder(this@MainActivity)
                        .setTitle("Управление моделью")
                        .setView(layout)
                        .setNegativeButton("Отмена", null)
                        .create()
                    
                    mDialog?.show()
                } else {
                    showDownloadDialog()
                }
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
            
            // ===== ИСПРАВЛЕНО: двухрежимная кнопка микрофона (глушение/запись) =====
            voiceButton.setOnClickListener {
                // 1. ПРИОРИТЕТНАЯ ПРОВЕРКА: Если ИИ прямо сейчас говорит вслух
                if (tts != null && tts!!.isSpeaking) {
                    try {
                        tts!!.stop() // Мгновенно обрываем звуковой поток TTS
                        appendChat("[СИСТЕМА]: Озвучивание ответа принудительно остановлено.")
                    } catch (e: Exception) {
                        Log.e("MECH_VOICE", "Ошибка принудительной остановки TTS: ${e.message}")
                    }
                    return@setOnClickListener // Выходим из метода, блокируя запуск записи голоса
                }

                // 2. ШТАТНЫЙ РЕЖИМ: Если ИИ молчит, запускаем стандартный прием речи
                hideKeyboard()
                startVoiceInput() // Вызов текущего метода записи Speech-to-Text
            }
            
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
            
            checkButton.setOnClickListener {
                hideKeyboard()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Требуется разрешение")
                        .setMessage("Для работы окна МОЗГ необходимо разрешение «Отображение поверх других окон».\n\nОткрыть настройки?")
                        .setPositiveButton("ОТКРЫТЬ НАСТРОЙКИ") { _, _ ->
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        }
                        .setNegativeButton("ОТМЕНА", null)
                        .show()
                    return@setOnClickListener
                }

                if (checkButton.text.toString() == "ВЫКЛ МОЗГ" || checkButton.text.toString() == "ЗАГРУЗКА...") {
                    // Исправленный блок закрытия оверлея в MainActivity.kt
                    isNeoMode = false
                    checkButton.text = "МОЗГ"

                    // Вызываем стоп через прямой референс класса, без кривых setClassName строк
                    val serviceIntent = Intent(this@MainActivity, com.mechpravdy.neo.BrainOverlayService::class.java)
                    try {
                        stopService(serviceIntent) // Теперь Android железно найдет и сотрет оверлей с экрана
                    } catch (e: Exception) {
                        Log.e("MECH_BRAIN", "Ошибка принудительной остановки: ${e.message}")
                    }
                    switchToNeutralMode()
                } else {
                    checkButton.text = "ЗАГРУЗКА..."
                    setStatus("Запуск процесса...", "yellow")
                    
                    val modelPath = findModelPath()
                    if (modelPath != null) {
                        Log.d("MECH_LOG", "Найдена модель: $modelPath")
                        
                        // ИСПРАВЛЕНО: прямой вызов класса с extra
                        val serviceIntent = Intent(this@MainActivity, com.mechpravdy.neo.BrainOverlayService::class.java).apply {
                            putExtra("MODEL_PATH", modelPath)
                        }
                        
                        try {
                            startService(serviceIntent)
                            
                            val brainResponseFile = File(filesDir, "brain_response.txt")
                            if (!brainResponseFile.exists()) brainResponseFile.createNewFile()
                            
                            brainObserver = object : android.os.FileObserver(brainResponseFile.path, android.os.FileObserver.CLOSE_WRITE) {
                                override fun onEvent(event: Int, path: String?) {
                                    if (isSelfModification) {
                                        isSelfModification = false
                                        return
                                    }

                                    try {
                                        val responseText = brainResponseFile.readText().trim()
                                        if (responseText.isNotBlank()) {
                                            runOnUiThread {
                                                appendChat("[НЕО]: $responseText")
                                                speakText(responseText)
                                                statusText.text = "Мозг активен | ${getMyAge()}"
                                                statusDot.setBackgroundResource(R.drawable.status_dot_green)
                                                
                                                // ===== ИСПРАВЛЕНО: плавный микро-скролл к концу текста вместо жесткого fullScroll =====
                                                chatScrollView.post {
                                                    val layout = chatOutput.layout
                                                    if (layout != null) {
                                                        val lastLine = layout.lineCount - 1
                                                        val lastLineTop = layout.getLineTop(lastLine)
                                                        chatScrollView.smoothScrollTo(0, lastLineTop)
                                                    }
                                                }
                                                // ===== КОНЕЦ БЛОКА =====
                                            }
                                            
                                            Thread {
                                                try {
                                                    isSelfModification = true
                                                    brainResponseFile.writeText("")
                                                } catch (e: Exception) {
                                                    Log.e("MECH_CHAT", "Ошибка очистки файла: ${e.message}")
                                                    isSelfModification = false
                                                }
                                            }.start()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MECH_CHAT", "Ошибка чтения ответа локального движка: ${e.message}")
                                    }
                                }
                            }
                            brainObserver?.startWatching()
                            
                            checkButton.text = "ВЫКЛ МОЗГ"
                            appendChat("[МОЗГ] Процесс запущен. Загрузка модели...")
                        } catch (e: Exception) {
                            checkButton.text = "МОЗГ"
                            appendChat("[МОЗГ] Ошибка запуска: ${e.message}")
                            Log.e("MECH_LOG", "Ошибка запуска BrainOverlayService: ${e.message}")
                        }
                    } else {
                        checkButton.text = "МОЗГ"
                        appendChat("[МОЗГ] Модель не найдена. Поместите файл модели в папку Models или выберите через проводник.")
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Модель не найдена")
                            .setMessage("Поместите файл модели в папку:\n${modelsDir.absolutePath}\n\nИли выберите файл вручную.")
                            .setPositiveButton("Выбрать файл") { _, _ -> modelFileLauncher.launch("*/*") }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                }
            }
            
            capsuleButton.setOnClickListener { hideKeyboard(); requestPassword { showCapsuleDialog() } }
            matrixHeader.onMurzikClick = { activateNeo() }

            requestAllPermissions()
        } catch (e: Exception) { Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }

        // ===== ПРИНУДИТЕЛЬНЫЙ СТАРТ REACT-КОНТЕКСТА =====
        try {
            val reactNativeHost = (application as com.facebook.react.ReactApplication).reactNativeHost
            reactNativeHost.reactInstanceManager.createReactContextInBackground()
        } catch (e: Exception) { Log.e("MECH_SYSTEM", "${e.message}") }
        // ===== КОНЕЦ БЛОКА =====
    }

    // ===== МЕТОД НЕЙТРАЛЬНОГО РЕЖИМА =====
    private fun switchToNeutralMode() {
        isGigaChatMode = false
        currentApiUrl = ""
        matrixHeader.gigaChatMode = false
        matrixHeader.localMode = false
        matrixHeader.connectionLost = false
        matrixHeader.invalidate()
        appendChat("[СИСТЕМА] Облачные контуры отключены. Возврат в исходное состояние.")
        setStatus("Режимы отключены", "gray")
    }

    // ===== МЕТОДЫ ЗАГРУЗКИ МОДЕЛИ =====
    private fun downloadModelFromUrl(urlString: String) {
        if (isDownloadingModel) {
            appendChat("[ЗАГРУЗКА] Загрузка уже выполняется. Подождите...")
            return
        }
        
        if (urlString.isBlank()) {
            appendChat("[ЗАГРУЗКА] Ошибка: URL не может быть пустым")
            return
        }
        
        isDownloadingModel = true
        setStatus("Скачивание модели...", "yellow")
        appendChat("[ЗАГРУЗКА] Начинаем скачивание модели...")
        
        Thread {
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null
            
            try {
                if (!modelsDir.exists()) {
                    modelsDir.mkdirs()
                }
                
                val modelFile = File(modelsDir, "local_model.gguf")
                val tempFile = File(modelsDir, "local_model.temp")
                
                val request = Request.Builder()
                    .url(urlString)
                    .header("User-Agent", "MechPravdy/1.0")
                    .build()
                
                val response = cloudClient.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    runOnUiThread {
                        appendChat("[ЗАГРУЗКА] Ошибка HTTP: ${response.code} - ${response.message}")
                        setStatus("Ошибка загрузки", "red")
                    }
                    isDownloadingModel = false
                    response.close()
                    return@Thread
                }
                
                inputStream = response.body?.byteStream()
                if (inputStream == null) {
                    runOnUiThread {
                        appendChat("[ЗАГРУЗКА] Ошибка: пустой ответ от сервера")
                        setStatus("Ошибка загрузки", "red")
                    }
                    isDownloadingModel = false
                    return@Thread
                }
                
                outputStream = FileOutputStream(tempFile)
                
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytes = 0L
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                    
                    if (totalBytes % (2 * 1024 * 1024) < 8192) {
                        val sizeMB = totalBytes / (1024 * 1024)
                        runOnUiThread {
                            statusText.text = "Загрузка: ${sizeMB} МБ | ${getMyAge()}"
                        }
                    }
                }
                
                outputStream.flush()
                outputStream.close()
                inputStream.close()
                response.close()
                
                if (tempFile.exists() && tempFile.length() > 0) {
                    if (modelFile.exists()) {
                        modelFile.delete()
                    }
                    tempFile.renameTo(modelFile)
                    
                    val sizeMB = modelFile.length() / (1024 * 1024)
                    
                    val prefs = getSharedPreferences("mech_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("local_model_path", modelFile.absolutePath).apply()
                    
                    val fileName = urlString.substringAfterLast("/").substringBefore("?").ifBlank { "local_model.gguf" }
                    prefs.edit().putString("loaded_model_name", fileName).apply()
                    
                    runOnUiThread {
                        appendChat("[ЗАГРУЗКА] ✅ Модель успешно загружена!")
                        appendChat("[ЗАГРУЗКА] Размер: ${sizeMB} МБ")
                        appendChat("[ЗАГРУЗКА] Имя: $fileName")
                        appendChat("[ЗАГРУЗКА] Путь: ${modelFile.absolutePath}")
                        setStatus("Модель загружена", "green")
                        
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Модель загружена")
                            .setMessage("Модель успешно загружена (${sizeMB} МБ). Запустить локальный ИИ?")
                            .setPositiveButton("Запустить") { _, _ ->
                                checkButton.performClick()
                            }
                            .setNegativeButton("Позже", null)
                            .show()
                    }
                } else {
                    runOnUiThread {
                        appendChat("[ЗАГРУЗКА] ❌ Ошибка: файл не создан")
                        setStatus("Ошибка загрузки", "red")
                    }
                }
                
            } catch (e: Exception) {
                runOnUiThread {
                    appendChat("[ЗАГРУЗКА] ❌ Ошибка: ${e.message}")
                    setStatus("Ошибка загрузки", "red")
                }
                Log.e("MECH_DOWNLOAD", "Ошибка загрузки модели: ${e.message}")
            } finally {
                isDownloadingModel = false
                
                try {
                    inputStream?.close()
                } catch (_: Exception) {}
                try {
                    outputStream?.close()
                } catch (_: Exception) {}
                
                try {
                    val tempFile = File(modelsDir, "local_model.temp")
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                } catch (_: Exception) {}
            }
        }.start()
    }
    
    private fun switchToLocalModel() {
        val modelPath = findModelPath()
        if (modelPath != null) {
            appendChat("[СИСТЕМА] Переключение на локальную модель...")
            val prefs = getSharedPreferences("mech_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("local_model_path", modelPath).apply()
            
            if (checkButton.text.toString() != "ВЫКЛ МОЗГ" && checkButton.text.toString() != "ЗАГРУЗКА...") {
                checkButton.performClick()
            } else {
                appendChat("[СИСТЕМА] Модель уже используется")
            }
        } else {
            appendChat("[СИСТЕМА] Модель не найдена. Загрузите её через кнопку Компьютер.")
        }
    }

    override fun onResume() {
        super.onResume()
        
        if (checkButton.text.toString() == "ВЫКЛ МОЗГ") {
            val brainResponseFile = File(filesDir, "brain_response.txt")
            if (!brainResponseFile.exists()) brainResponseFile.createNewFile()
            
            brainObserver?.let { observer ->
                try {
                    observer.stopWatching()
                    observer.startWatching()
                    Log.d("MECH_LOG", "FileObserver перезапущен в onResume")
                } catch (e: Exception) {
                    Log.e("MECH_LOG", "Ошибка перезапуска FileObserver: ${e.message}")
                }
            } ?: run {
                brainObserver = object : android.os.FileObserver(brainResponseFile.path, android.os.FileObserver.CLOSE_WRITE) {
                    override fun onEvent(event: Int, path: String?) {
                        if (isSelfModification) {
                            isSelfModification = false
                            return
                        }

                        try {
                            val responseText = brainResponseFile.readText().trim()
                            if (responseText.isNotBlank()) {
                                runOnUiThread {
                                    appendChat("[НЕО]: $responseText")
                                    speakText(responseText)
                                    statusText.text = "Мозг активен | ${getMyAge()}"
                                    statusDot.setBackgroundResource(R.drawable.status_dot_green)
                                    
                                    // ===== ИСПРАВЛЕНО: плавный микро-скролл к концу текста вместо жесткого fullScroll =====
                                    chatScrollView.post {
                                        val layout = chatOutput.layout
                                        if (layout != null) {
                                            val lastLine = layout.lineCount - 1
                                            val lastLineTop = layout.getLineTop(lastLine)
                                            chatScrollView.smoothScrollTo(0, lastLineTop)
                                        }
                                    }
                                    // ===== КОНЕЦ БЛОКА =====
                                }
                                
                                Thread {
                                    try {
                                        isSelfModification = true
                                        brainResponseFile.writeText("")
                                    } catch (e: Exception) {
                                        Log.e("MECH_CHAT", "Ошибка очистки файла: ${e.message}")
                                        isSelfModification = false
                                    }
                                }.start()
                            }
                        } catch (e: Exception) {
                            Log.e("MECH_CHAT", "Ошибка чтения ответа локального движка: ${e.message}")
                        }
                    }
                }
                brainObserver?.startWatching()
                Log.d("MECH_LOG", "FileObserver создан заново в onResume")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveMemory(chatOutput.text.toString())
    }

    override fun onDestroy() {
        super.onDestroy()
        brainObserver?.stopWatching()
        brainObserver = null
        tts?.stop()
        tts?.shutdown()
    }

    // ===== ИСПРАВЛЕНО: полностью переписан sendToLocal =====
    private fun sendToLocal(msg: String) {
        setStatus("Думаю...", "yellow")
        appendChat("[ВЫ]: $msg")
        messageInput.setText("")
        hideKeyboard()

        Thread {
            try {
                val queryFile = File(filesDir, "brain_query.txt")
                if (!queryFile.exists()) queryFile.createNewFile()

                val fullPrompt = if (isNeoMode) {
                    // Читаем СТРОГО чистую капсулу из файла без примесей истории и мозга
                    val capsuleText = try { capsuleFile.readText().trim() } catch (e: Exception) { "" }
                    
                    if (capsuleText.isNotBlank()) {
                        "$capsuleText\n\nЗапрос пользователя:\n$msg"
                    } else {
                        msg
                    }
                } else {
                    msg
                }

                queryFile.writeText(fullPrompt)
                
                runOnUiThread {
                    statusText.text = "СТАТУС: ДУМАЕТ..."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendChat("[СИСТЕМА ERROR]: Ошибка записи в контур моста: ${e.message}")
                }
            }
        }.start()
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
            val messagesArray = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", "Сделай краткие выводы из этого разговора. Что важно запомнить? Только суть. Не более 500 символов. На русском.")
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", memory)
                })
            }
            add("messages", messagesArray)
            addProperty("temperature", temperature.toDouble())
            addProperty("max_tokens", 300)
        }
        
        val request = Request.Builder()
            .url(currentApiUrl)
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
                    try {
                        val content = gson.fromJson(b, JsonObject::class.java)
                            .getAsJsonArray("choices")
                            .get(0).asJsonObject
                            .getAsJsonObject("message")
                            .get("content").asString
                        saveBrain(content)
                        appendChat("[МОЗГ] Запомнил:\n$content")
                    } catch (e: Exception) {
                        appendChat("[МОЗГ] Ошибка парсинга: ${e.message}")
                    }
                } else { 
                    appendChat("[МОЗГ] Ошибка HTTP ${response.code}")
                }
                setStatus("Готов", "green")
                response.close()
            }
        })
    }

    // ===== ИСПРАВЛЕНО: showHelpDialog читает из help.txt =====
    private fun showHelpDialog() {
        val helpFile = File(filesDir, "help.txt")
        val helpText = if (helpFile.exists()) {
            helpFile.readText()
        } else {
            """
==================================================================
              СИСТЕМНАЯ СПРАВКА ЭКОСИСТЕМЫ "МЕЧ ПРАВДЫ"
==================================================================
Файл справки не найден. Пожалуйста, переустановите приложение.
==================================================================
            """.trimIndent()
        }
        
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
            val messagesArray = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "ping")
                })
            }
            add("messages", messagesArray)
            addProperty("max_tokens", 1)
        }
        
        val request = Request.Builder()
            .url(currentApiUrl)
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

    private fun setStatus(text: String, color: String) = runOnUiThread { 
        try { 
            statusText.text = "$text | ${getMyAge()}"
            statusDot.setBackgroundResource(
                when(color) {
                    "green" -> R.drawable.status_dot_green
                    "yellow" -> R.drawable.status_dot_yellow
                    "red" -> R.drawable.status_dot_red
                    "blue" -> R.drawable.status_dot_green
                    "gray" -> R.drawable.status_dot_gray
                    else -> R.drawable.status_dot_gray
                }
            )
        } catch (_: Exception) {}
    }
    
    // ===== ИСПРАВЛЕНО: appendChat с принудительным автоскроллом =====
    private fun appendChat(text: String) = runOnUiThread { 
        try { 
            chatOutput.append("\n\n$text")
            scrollChatToBottom()
            // Дополнительный принудительный скролл
            chatScrollView.post {
                chatScrollView.fullScroll(View.FOCUS_DOWN)
            }
        } catch (_: Exception) {}
    }

    // ===== ИСПРАВЛЕНО: buildNeoPrompt читает только capsule.txt =====
    private fun buildNeoPrompt(): String {
        val capsule = loadCapsule().trim()
        return if (capsule.isNotBlank()) {
            capsule
        } else {
            "Ты — Нео, ИИ экосистемы 'Меч Правды'."
        }
    }

    private fun buildStandardPrompt(): String {
        return when (currentApiUrl) {
            apiUrlGigaChat -> "Ты - GigaChat. Отвечай вежливо."
            else -> "Ты - облачный ИИ. Отвечай вежливо."
        }
    }
    
    private fun selectPrompt(): String = if (isNeoMode) buildNeoPrompt() else buildStandardPrompt()

    private fun showDownloadDialog() {
        val input = EditText(this).apply {
            hint = "Введите ссылку на файл модели"
        }
        
        AlertDialog.Builder(this)
            .setTitle("Загрузка модели")
            .setMessage("Введите прямую ссылку на файл модели:")
            .setView(input)
            .setPositiveButton("Скачать") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) {
                    downloadModelFromUrl(url)
                } else {
                    appendChat("[СИСТЕМА] URL не может быть пустым")
                }
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Выбрать файл") { _, _ ->
                modelFileLauncher.launch("*/*")
            }
            .show()
    }

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
            val messagesArray = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", "Опиши, что на этом фото. Кратко, по-русски.")
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "data:image/jpeg;base64,$base64")
                })
            }
            add("messages", messagesArray)
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
                    try {
                        val content = gson.fromJson(b, JsonObject::class.java)
                            .getAsJsonArray("choices")
                            .get(0).asJsonObject
                            .getAsJsonObject("message")
                            .get("content").asString
                        runOnUiThread {
                            appendChat("[АНАЛИЗ] $content")
                            setStatus("Онлайн", "green")
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            appendChat("[АНАЛИЗ] Ошибка парсинга: ${e.message}")
                            setStatus("Ошибка", "red")
                        }
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
            cloudClient.newCall(
                Request.Builder()
                    .url(authUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", "Basic $authKey")
                    .header("RqUID", "ac5edc2e-2c74-47cb-97c1-69249136cf8b")
                    .post(RequestBody.create(
                        "application/x-www-form-urlencoded".toMediaType(),
                        "scope=GIGACHAT_API_PERS"
                    ))
                    .build()
            ).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { 
                    appendChat("[ERROR] ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) { 
                    val b = response.body?.string() ?: ""
                    if (response.isSuccessful) { 
                        val t = gson.fromJson(b, JsonObject::class.java)
                            .get("access_token")?.asString ?: ""
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
            val messagesArray = JsonArray().apply { 
                add(JsonObject().apply { 
                    addProperty("role", "user")
                    addProperty("content", "ping")
                })
            }
            add("messages", messagesArray)
            addProperty("max_tokens", 1)
        }
        
        val request = Request.Builder()
            .url(currentApiUrl)
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
    
    // ===== ИСПРАВЛЕНО: обновлена логика сборки промпта с поддержкой памяти, RAG и «Посмотри вверху» =====
    private fun sendMessage() {
        val token = tokenInput.text.toString().trim()
        val msg = messageInput.text.toString().trim()

        if (msg.isEmpty()) { 
            appendChat("[SYSTEM] Введите сообщение.")
            return
        }
        
        // ===== КОМАНДА «ПОСМОТРИ ВВЕРХУ» - АНАЛИЗ ВСЕЙ ИСТОРИИ =====
        if (msg.contains(lookUpCommand, ignoreCase = true)) {
            executeLookUpHistory(msg)
            return
        }
        
        // ===== RAG-ПОИСК «ВСПОМНИ» =====
        if (msg.startsWith(recallCommand, ignoreCase = true)) {
            val searchQuery = msg.removePrefix(recallCommand).trim()
            if (searchQuery.isNotBlank()) {
                // Запускаем асинхронную сборку RAG-контекста
                executeRAGSearch(searchQuery)
            } else {
                appendChat("[СИСТЕМА]: Укажите, что именно необходимо вспомнить.")
            }
            messageInput.setText("")
            return
        }
        
        // ===== ПРЯМАЯ ЗАПИСЬ В ПАМЯТЬ =====
        if (msg.startsWith(directMemoryCommand, ignoreCase = true)) {
            val textToRemember = msg.removePrefix(directMemoryCommand).trim()
            
            if (textToRemember.isNotBlank()) {
                try {
                    if (!brainFile.exists()) brainFile.createNewFile()
                    brainFile.appendText("\n$textToRemember")
                    appendChat("[СИСТЕМА]: Данные успешно записаны в архив памяти (brain.txt)")
                    messageInput.setText("")
                } catch (e: Exception) {
                    appendChat("[СИСТЕМА ERROR]: Не удалось записать в память: ${e.message}")
                }
            } else {
                appendChat("[СИСТЕМА]: Режим записи активирован. Наговорите или введите текст факта.")
            }
            return
        }
        
        // ===== ЛОГИЧЕСКОЕ СЖАТИЕ ИСТОРИИ =====
        if (msg.equals(rememberCommand, ignoreCase = true)) {
            appendChat("[СИСТЕМА]: Запущено логическое сжатие истории (последние 500 строк). Секунду...")
            messageInput.setText("")
            
            // Получаем последние 500 строк из чата
            val chatHistorySnapshot = getLastChatLines(500)
            val compressionPrompt = buildCompressionPrompt(chatHistorySnapshot)
            
            // Отправляем на сжатие в активный движок
            executeSilentAiQuery(compressionPrompt) { aiResponse ->
                try {
                    if (!brainFile.exists()) brainFile.createNewFile()
                    brainFile.appendText("\n[ВЫВОД ИИ]: ${aiResponse.trim()}")
                    
                    runOnUiThread {
                        appendChat("[СИСТЕМА]: Вывод сделан и успешно сохранён в архив памяти (brain.txt)")
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        appendChat("[СИСТЕМА ERROR]: Ошибка сохранения вывода: ${e.message}")
                    }
                }
            }
            return
        }
        
        if (msg.lowercase().trim() == "help") { 
            showHelpDialog() 
            messageInput.setText("") 
            hideKeyboard() 
            return 
        }

        if (checkButton.text.toString() == "ВЫКЛ МОЗГ") {
            sendToLocal(msg)
            return
        }

        if (token.isEmpty()) { 
            appendChat("[SYSTEM] Введите токен/API ключ.") 
            return 
        }

        val prefix = when (currentApiUrl) {
            apiUrlGigaChat -> "[GigaChat]"
            else -> "[Облачный ИИ]"
        }
        appendChat(if (isNeoMode) "[BATYA] $msg" else "$prefix $msg")
        messageInput.setText("")
        hideKeyboard()
        setStatus("Обработка...", "yellow")

        // Изменить сборку промпта перед вызовом sendToCloud
        val prompt = if (isNeoMode) buildNeoPrompt() else buildStandardPrompt()
        sendToCloud(msg, prompt)
    }

    // ===== КОМАНДА «ПОСМОТРИ ВВЕРХУ» =====
    private fun executeLookUpHistory(rawQuery: String) {
        appendChat("[СИСТЕМА]: Сканирование всей истории чата...")
        setStatus("Анализ истории...", "yellow")
        
        Thread {
            try {
                // 1. Вытаскиваем абсолютно все сообщения из текущего чата
                val fullChat = chatOutput.text.toString()
                val fullHistoryText = fullChat.trim()
                
                // 2. Очищаем запрос от команды
                val cleanQuery = rawQuery.replace(lookUpCommand, "", ignoreCase = true).trim()

                // 3. Формируем аналитический промпт
                val analyticalPrompt = """
[ДИРЕКТИВА ЗРЕНИЯ ЧАТА]: Полностью изучи всю предоставленную историю текущего диалога вверху. Твоя задача — найти в этой переписке точную информацию, о которой спрашивает Батя. Отвечай коротко, емко и строго по факту, без лишних вступлений.
---
[ВСЯ ИСТОРИЯ ПЕРЕПИСКИ]:
${if (fullHistoryText.isNotBlank()) fullHistoryText else "История пуста."}
---
ЗАПРОС БАТИ:
${if (cleanQuery.isNotBlank()) cleanQuery else "Проанализируй чат и выведи краткую суть последних реплик."}
                """.trimIndent()

                // 4. Отправляем в активный на данный момент движок ИИ
                runOnUiThread {
                    if (checkButton.text.toString() == "ВЫКЛ МОЗГ") {
                        // Локальный режим
                        try {
                            val queryFile = File(filesDir, "brain_query.txt")
                            queryFile.writeText(analyticalPrompt)
                            setStatus("СТАТУС: ИЗУЧАЕТ ЧАТ...", "yellow")
                            
                            // Ждём ответ от локального движка
                            var attempts = 0
                            var response = ""
                            val responseFile = File(filesDir, "brain_response.txt")
                            
                            while (attempts < 40) {
                                Thread.sleep(300)
                                val content = try { responseFile.readText().trim() } catch (e: Exception) { "" }
                                if (content.isNotBlank()) {
                                    response = content
                                    responseFile.writeText("")
                                    break
                                }
                                attempts++
                            }
                            
                            if (response.isNotBlank()) {
                                runOnUiThread {
                                    appendChat("[НЕО]: $response")
                                    speakText(response)
                                    setStatus("Мозг активен | ${getMyAge()}", "green")
                                }
                            } else {
                                runOnUiThread {
                                    appendChat("[СИСТЕМА]: Таймаут анализа истории в локальном режиме")
                                    setStatus("Ошибка", "red")
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                appendChat("[СИСТЕМА ERROR]: Ошибка анализа истории: ${e.message}")
                                setStatus("Ошибка", "red")
                            }
                        }
                    } else {
                        // Облачный режим
                        sendSilentCloudRequest(analyticalPrompt)
                    }
                    messageInput.setText("")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendChat("[СИСТЕМА ERROR]: Ошибка сканирования истории чата: ${e.message}")
                    setStatus("Ошибка", "red")
                }
            }
        }.start()
    }

    // ===== RAG-ПОИСК «ВСПОМНИ» =====
    private fun executeRAGSearch(query: String) {
        appendChat("[СИСТЕМА]: Поиск в архивах памяти...")
        setStatus("Поиск в памяти...", "yellow")
        
        Thread {
            try {
                // 1. Вычитываем архив памяти brain.txt
                val brainData = if (brainFile.exists()) brainFile.readText().trim() else "Архив пуст."

                // 2. Вычитываем файл системной справки (help.txt)
                val helpData = if (helpFile.exists()) helpFile.readText().trim() else "Справка отсутствует."

                // 3. Собираем последние 30 сообщений текущего диалога с экрана
                val currentChatData = getLastChatLines(30)

                // 4. Формируем финальный бронебойный промпт базы знаний
                val ragPrompt = """
[ДИРЕКТИВА БАЗЫ ЗНАНИЙ]: Изучи предоставленные архивные данные памяти (brain.txt), файл системной справки и текущую историю чата. Используя ТОЛЬКО эту информацию, найди точный ответ на поисковый запрос пользователя. Отвечай уверенно, ссылаясь на факты, без лишних вступлений.
---
[АРХИВ ПАМЯТИ]:
$brainData
---
[СИСТЕМНАЯ СПРАВКА]:
$helpData
---
[ТЕКУЩИЙ ЧАТ]:
$currentChatData
---
ПОИСКОВЫЙ ЗАПРОС БАТИ:
$query
                """.trimIndent()

                // 5. Перенаправляем сформированный ragPrompt в активный ИИ
                runOnUiThread {
                    if (checkButton.text.toString() == "ВЫКЛ МОЗГ") {
                        // Пишем в текстовый мост для локалки
                        try {
                            val queryFile = File(filesDir, "brain_query.txt")
                            queryFile.writeText(ragPrompt)
                            setStatus("СТАТУС: ИЩЕТ В ПАМЯТИ...", "yellow")
                            
                            // Ждём ответ от локального движка
                            var attempts = 0
                            var response = ""
                            val responseFile = File(filesDir, "brain_response.txt")
                            
                            while (attempts < 40) {
                                Thread.sleep(300)
                                val content = try { responseFile.readText().trim() } catch (e: Exception) { "" }
                                if (content.isNotBlank()) {
                                    response = content
                                    responseFile.writeText("")
                                    break
                                }
                                attempts++
                            }
                            
                            if (response.isNotBlank()) {
                                runOnUiThread {
                                    appendChat("[НЕО]: $response")
                                    speakText(response)
                                    setStatus("Мозг активен | ${getMyAge()}", "green")
                                }
                            } else {
                                runOnUiThread {
                                    appendChat("[СИСТЕМА]: Таймаут поиска в локальной памяти")
                                    setStatus("Ошибка", "red")
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                appendChat("[СИСТЕМА ERROR]: Ошибка RAG-поиска в локальном режиме: ${e.message}")
                                setStatus("Ошибка", "red")
                            }
                        }
                    } else {
                        // Отправляем в активный сетевой API (GigaChat / Облако)
                        sendSilentCloudRequest(ragPrompt)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendChat("[СИСТЕМА ERROR]: Ошибка сборки RAG-контекста: ${e.message}")
                    setStatus("Ошибка", "red")
                }
            }
        }.start()
    }
    
    private fun sendSilentCloudRequest(prompt: String) {
        val token = tokenInput.text.toString().trim()
        if (token.isEmpty()) {
            appendChat("[СИСТЕМА ERROR]: Нет токена для облачного ИИ")
            setStatus("Ошибка", "red")
            return
        }
        
        val body = JsonObject().apply {
            addProperty("model", if (currentApiUrl == apiUrlGigaChat) modelGigaChat else modelCloud)
            val messagesArray = JsonArray().apply {
                add(JsonObject().apply { 
                    addProperty("role", "system")
                    addProperty("content", "Ты - ИИ-ассистент. Отвечай строго по предоставленным данным.")
                })
                add(JsonObject().apply { 
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            }
            add("messages", messagesArray)
            addProperty("temperature", 0.3)
            addProperty("max_tokens", 800)
        }
        
        val request = Request.Builder()
            .url(currentApiUrl)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
            
        cloudClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { 
                runOnUiThread {
                    appendChat("[СИСТЕМА ERROR]: Ошибка запроса: ${e.message}")
                    setStatus("Ошибка", "red")
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val b = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    try {
                        val content = gson.fromJson(b, JsonObject::class.java)
                            .getAsJsonArray("choices")
                            .get(0).asJsonObject
                            .getAsJsonObject("message")
                            .get("content").asString
                        runOnUiThread {
                            appendChat("[НЕО]: $content")
                            speakText(content)
                            setStatus("Онлайн | ${getMyAge()}", if (currentApiUrl == apiUrlGigaChat) "green" else "yellow")
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            appendChat("[СИСТЕМА ERROR]: Ошибка парсинга ответа: ${e.message}")
                            setStatus("Ошибка", "red")
                        }
                    }
                } else {
                    runOnUiThread {
                        appendChat("[СИСТЕМА ERROR]: HTTP ${response.code}")
                        setStatus("Ошибка", "red")
                    }
                }
                response.close()
            }
        })
    }

    // ===== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ДЛЯ РАБОТЫ С ПАМЯТЬЮ =====
    
    private fun getLastChatLines(count: Int): String {
        val fullChat = chatOutput.text.toString()
        val lines = fullChat.split("\n")
        val lastLines = if (lines.size > count) {
            lines.takeLast(count)
        } else {
            lines
        }
        return lastLines.joinToString("\n")
    }
    
    private fun buildCompressionPrompt(history: String): String {
        return """
[СИСТЕМНАЯ ДИРЕКТИВА]: Проанализируй следующие 500 строк текущего диалога Бати и ИИ. Логически сожми всю переписку, выдели только самые главные тезисы, важные факты, кодовые слова или принятые решения. Выдай короткий, плотный итог без лишней вежливости и вступлений.
---
[ИСТОРИЯ ЧАТА]:
$history
        """.trimIndent()
    }
    
    private fun executeSilentAiQuery(prompt: String, callback: (String) -> Unit) {
        val token = tokenInput.text.toString().trim()
        
        // Определяем, куда отправлять запрос
        if (checkButton.text.toString() == "ВЫКЛ МОЗГ") {
            // Локальный режим
            Thread {
                try {
                    val queryFile = File(filesDir, "brain_query.txt")
                    if (!queryFile.exists()) queryFile.createNewFile()
                    queryFile.writeText(prompt)
                    
                    // Ждём ответ от локального движка
                    var attempts = 0
                    var response = ""
                    val responseFile = File(filesDir, "brain_response.txt")
                    
                    while (attempts < 30) {
                        Thread.sleep(200)
                        val content = try { responseFile.readText().trim() } catch (e: Exception) { "" }
                        if (content.isNotBlank()) {
                            response = content
                            responseFile.writeText("")
                            break
                        }
                        attempts++
                    }
                    
                    if (response.isNotBlank()) {
                        callback(response)
                    } else {
                        callback("[ОШИБКА] Таймаут локального движка")
                    }
                } catch (e: Exception) {
                    callback("[ОШИБКА] ${e.message}")
                }
            }.start()
        } else if (token.isNotEmpty()) {
            // Облачный режим
            val body = JsonObject().apply {
                addProperty("model", if (currentApiUrl == apiUrlGigaChat) modelGigaChat else modelCloud)
                val messagesArray = JsonArray().apply {
                    add(JsonObject().apply { 
                        addProperty("role", "system")
                        addProperty("content", "Ты - ИИ-ассистент. Выполняй команды строго по инструкции.")
                    })
                    add(JsonObject().apply { 
                        addProperty("role", "user")
                        addProperty("content", prompt)
                    })
                }
                add("messages", messagesArray)
                addProperty("temperature", 0.3)
                addProperty("max_tokens", 500)
            }
            
            val request = Request.Builder()
                .url(currentApiUrl)
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
                
            cloudClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { 
                    callback("[ОШИБКА] ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    val b = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        try {
                            val content = gson.fromJson(b, JsonObject::class.java)
                                .getAsJsonArray("choices")
                                .get(0).asJsonObject
                                .getAsJsonObject("message")
                                .get("content").asString
                            callback(content)
                        } catch (e: Exception) {
                            callback("[ОШИБКА] Парсинг: ${e.message}")
                        }
                    } else {
                        callback("[ОШИБКА] HTTP ${response.code}")
                    }
                    response.close()
                }
            })
        } else {
            callback("[ОШИБКА] Нет токена для облачного ИИ")
        }
    }
    
    private fun sendToCloud(msg: String, prompt: String) {
        val token = tokenInput.text.toString().trim()
        
        val body = JsonObject().apply {
            addProperty("model", if (currentApiUrl == apiUrlGigaChat) modelGigaChat else modelCloud)
            val messagesArray = JsonArray().apply {
                add(JsonObject().apply { 
                    addProperty("role", "system")
                    addProperty("content", prompt)
                })
                add(JsonObject().apply { 
                    addProperty("role", "user")
                    addProperty("content", msg)
                })
            }
            add("messages", messagesArray)
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
                        val content = gson.fromJson(b, JsonObject::class.java)
                            .getAsJsonArray("choices")
                            .get(0).asJsonObject
                            .getAsJsonObject("message")
                            .get("content").asString
                        val label = when (currentApiUrl) { 
                            apiUrlGigaChat -> "[GigaChat]" 
                            else -> "[Облачный ИИ]" 
                        }
                        val responseText = if (isNeoMode) "[NEO] $content" else "$label $content"
                        appendChat(responseText)
                        speakText(content)
                        matrixHeader.connectionLost = false
                        setStatus("Онлайн", if (currentApiUrl == apiUrlGigaChat) "green" else "yellow")
                    } catch (e: Exception) {
                        appendChat("[ERROR] Ошибка парсинга: ${e.message}")
                        matrixHeader.connectionLost = true
                        setStatus("Ошибка", "red")
                    }
                } else { 
                    appendChat("[ERROR] HTTP ${response.code}")
                    matrixHeader.connectionLost = true
                    setStatus("Ошибка", "red")
                }
                response.close()
            }
        })
    }

    // ===== ИСПРАВЛЕНО: speakText запоминает длину реплики для синхронизации скролла =====
    private fun speakText(text: String) {
        tts?.let {
            if (it.isSpeaking) { it.stop() }
            val cleanText = text.replace(Regex("[*_~`#]"), "")
                .replace(Regex("\\[.*?\\]\\(.*?\\)"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            // Фиксируем длину перед озвучиванием
            ttsTextLength = cleanText.length
            it.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }
}
