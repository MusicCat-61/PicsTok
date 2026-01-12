package com.musiccat.picstok

import android.app.Application
import android.content.*
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.ImageLoader

import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.compose.rememberAsyncImagePainter
import androidx.compose.runtime.mutableIntStateOf
import com.github.awxkee.avifcoil.decoder.HeifDecoder
import android.os.Build
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.directory
import coil3.request.allowHardware
import coil3.request.error
import coil3.request.placeholder
import java.io.IOException
import com.musiccat.picstok.ui.theme.PicsTokTheme
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.core.content.edit
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.ui.text.style.TextOverflow
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import java.io.ByteArrayOutputStream
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextAlign
import coil3.BitmapImage
import coil3.decode.BitmapFactoryDecoder
import coil3.Image
import coil3.toBitmap
import okhttp3.Request
import okio.Buffer
import okio.buffer
import okio.BufferedSource
import androidx.compose.ui.draw.rotate
import com.musiccat.picstok.GallerySortOrder
import com.musiccat.picstok.GalleryGroup
import kotlinx.io.files.SystemFileSystem
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


// --- API ---
interface TikTokApi {
    @POST("parse")
    suspend fun startParse(@Body body: Map<String, String>): Map<String, String>
    @GET("status/{id}")
    suspend fun getStatus(@Path("id") id: String): Map<String, Any>
}

// --- Helper Functions ---
fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0) return "0 B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = sizeBytes.toDouble()
    var unitIndex = 0

    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }

    return String.format("%.2f %s", size, units[unitIndex])
}

data class GalleryItem(val uri: Uri, val size: Long, val date: Long)
data class ArchiveItem(val file: File)


data class CachedImage(
    val url: String,
    val data: ByteArray,  // Байты изображения
    val mimeType: String, // MIME-тип
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CachedImage
        if (url != other.url) return false
        return true
    }

    override fun hashCode(): Int = url.hashCode()
}
class ParserState {
    var currentTab by mutableIntStateOf(0)
    var url by mutableStateOf("")
    var isWorking by mutableStateOf(false)
    var isFinished by mutableStateOf(false)
    var resultUrls by mutableStateOf<List<String>>(emptyList())
    var processedCount by mutableIntStateOf(0)
    var foundCount by mutableIntStateOf(0)
    var downloadProgress by mutableFloatStateOf(-1f)
    var showDownloadAllDialog by mutableStateOf(false)
    var showZipDialog by mutableStateOf(false)
    var scrollState by mutableStateOf(0)

    // Настройки
    var themeMode by mutableStateOf("Системная")
    var useMaterialYou by mutableStateOf(false)

    var isDownloading by mutableStateOf(false)
    var isZipOperation by mutableStateOf(false)
    var currentDownloadJob: Job? = null

    val cachedImages = mutableMapOf<String, CachedImage>()

    // Очистка кэша при новом парсинге
    fun clearCache() {
        cachedImages.clear()
    }
}

class MainActivity : ComponentActivity() {
    lateinit var imageLoader: ImageLoader
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Загрузка настроек
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val initialTheme = prefs.getString("theme_mode", "Системная") ?: "Системная"
        val initialMaterialYou = prefs.getBoolean("material_you", false)

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)  // Увеличено для медленных соединений
            .readTimeout(60, TimeUnit.SECONDS)     // Дольше ждать ответа от сервера
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)        // Автоматический retry при ошибках соединения
            .build()

        val api = Retrofit.Builder()
            .baseUrl("http://206.245.159.225:8000/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()
            .create(TikTokApi::class.java)

        imageLoader = ImageLoader.Builder(this)
            .components {
                add(HeifDecoder.Factory())  // Регистрируем HEIC/HEIF декодер
                // Если нужно, добавь другие (GIF, SVG и т.д.)
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(this, 0.25)  // 25% от доступной памяти приложения
                    .build()
            }


            .build()


        setContent {
            val state = remember { ParserState().apply {
                themeMode = initialTheme
                useMaterialYou = initialMaterialYou
            }}

            // Сохранение настроек при изменении
            LaunchedEffect(state.themeMode, state.useMaterialYou) {
                prefs.edit {
                    putString("theme_mode", state.themeMode)
                        .putBoolean("material_you", state.useMaterialYou)
                }
            }

            PicsTokTheme(themeMode = state.themeMode, useDynamicColor = state.useMaterialYou) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(state, api)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: ParserState, api: TikTokApi) {
    var showSettings by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedSize by remember { mutableLongStateOf(0L) }
    var selectedCount by remember { mutableIntStateOf(0) }
    var galleryTotalItems by remember { mutableIntStateOf(0) }
    var archivesTotalItems by remember { mutableIntStateOf(0) }
    var onSelectAllTrigger by remember { mutableStateOf(false) }
    var onDeleteTrigger by remember { mutableStateOf(false) }

    // Вычисляем общее количество в зависимости от вкладки
    val totalItemsInCurrentTab = when (state.currentTab) {
        1 -> galleryTotalItems
        2 -> archivesTotalItems
        else -> 0
    }

    // Определяем текст кнопки "Выбрать все"/"Снять все"
    val selectAllButtonText = if (selectedCount == totalItemsInCurrentTab && totalItemsInCurrentTab > 0) {
        stringResource(R.string.btn_deselect_all)
    } else {
        stringResource(R.string.btn_select_all)
    }

    Scaffold(
        topBar = {
            if ((state.currentTab == 1 || state.currentTab == 2) && isSelectionMode) {
                TopAppBar(
                    title = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.gallery_selected_count_items, selectedCount),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 18.sp
                            )
                            Text(
                                stringResource(R.string.gallery_selected_count_size, formatFileSize(selectedSize)),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                    },
                    navigationIcon = {
                        TextButton(
                            onClick = {
                                onSelectAllTrigger = true
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(selectAllButtonText, fontWeight = FontWeight.Bold)
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { isSelectionMode = false },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(stringResource(R.string.btn_cancel), fontWeight = FontWeight.Bold)
                        }
                    }
                )
            } else {
                CenterAlignedTopAppBar(
                    title = {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.ExtraBold)
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(painterResource(R.drawable.ic_settings), null)
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (state.currentTab == 1 || state.currentTab == 2) {
                // Показываем одну кнопку для включения режима выбора
                if (!isSelectionMode) {
                    FloatingActionButton(
                        onClick = { isSelectionMode = true },
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(painterResource(R.drawable.ic_action_menu), null)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.currentTab == 0,
                    onClick = {
                        state.currentTab = 0
                        isSelectionMode = false
                    },
                    icon = { Icon(painterResource(R.drawable.ic_nav_parser), null) },
                    label = { Text(stringResource(R.string.tab_parser)) }
                )
                NavigationBarItem(
                    selected = state.currentTab == 1,
                    onClick = {
                        state.currentTab = 1
                        isSelectionMode = false
                    },
                    icon = { Icon(painterResource(R.drawable.ic_nav_gallery), null) },
                    label = { Text(stringResource(R.string.tab_gallery)) }
                )
                NavigationBarItem(
                    selected = state.currentTab == 2,
                    onClick = {
                        state.currentTab = 2
                        isSelectionMode = false
                    },
                    icon = { Icon(painterResource(R.drawable.ic_archive), null) },
                    label = { Text(stringResource(R.string.tab_archives)) }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (state.currentTab) {
                0 -> ParserScreen(state, api)
                1 -> GalleryScreen(
                    isSelectionMode = isSelectionMode,
                    onSelectionChange = { count, size ->
                        selectedCount = count
                        selectedSize = size
                    },
                    onTotalItemsChanged = { total -> galleryTotalItems = total },
                    onDeleteRequest = onDeleteTrigger,
                    onSelectAllRequest = onSelectAllTrigger,
                    onOperationFinished = { wasDeleted ->
                        onDeleteTrigger = false
                        onSelectAllTrigger = false
                        if (wasDeleted) {
                            isSelectionMode = false
                        }
                    }
                )
                2 -> ArchivesScreen(
                    isSelectionMode = isSelectionMode,
                    onSelectionChange = { count, size ->
                        selectedCount = count
                        selectedSize = size
                    },
                    onTotalItemsChanged = { total -> archivesTotalItems = total },
                    onDeleteRequest = onDeleteTrigger,
                    onSelectAllRequest = onSelectAllTrigger,
                    onOperationFinished = { wasDeleted ->
                        onDeleteTrigger = false
                        onSelectAllTrigger = false
                        if (wasDeleted) {
                            isSelectionMode = false
                        }
                    }
                )
            }
        }
    }

    if (showSettings) {
        SettingsSheet(state) { showSettings = false }
    }
}

private suspend fun checkClipboard(context: Context, state: ParserState) {
    withContext(Dispatchers.Main) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip

            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString() ?: ""

                // Убедимся, что это действительно TikTok ссылка
                val isTikTokUrl = text.contains("tiktok.com") &&
                        (text.contains("/video/") || text.contains("/photo/") ||
                                text.contains("/@") || text.contains("tiktok.com/t/") ||
                                text.contains("vm.tiktok.com") || text.contains("vt.tiktok.com"))

                if (isTikTokUrl) {
                    // Вставляем только если поле пустое
                    if (state.url.isEmpty() && !state.isWorking && !state.isFinished) {
                        state.url = text.trim().trimEnd('/')
                        // Показываем уведомление что ссылка вставлена

                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Clipboard", "Ошибка при проверке буфера: ${e.message}")
        }
    }
}
@Composable
fun ParserScreen(state: ParserState, api: TikTokApi) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lazyGridState = rememberLazyGridState(initialFirstVisibleItemIndex = state.scrollState)
    LaunchedEffect(lazyGridState.firstVisibleItemIndex) {
        state.scrollState = lazyGridState.firstVisibleItemIndex
    }

    val networkChecker = remember { NetworkChecker(context) }


    val imageLoader = remember {
        (context.applicationContext as Application).imageLoader
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    var pollingJob by remember { mutableStateOf<Job?>(null) }
    var selectedUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var validImageUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var showNothingSelectedDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Кэш для уже проверенных URL
    var checkedUrlsCache by remember { mutableStateOf<Set<String>>(emptySet()) }

    // ВАЖНО: Сохраняем состояние при уничтожении
    DisposableEffect(Unit) {
        onDispose {
            // Сохраняем текущие данные в state для восстановления
            if (validImageUrls.isNotEmpty()) {
                state.resultUrls = validImageUrls
                state.foundCount = validImageUrls.size
            }
        }
    }

    // Восстанавливаем состояние при возврате на вкладку
    LaunchedEffect(state.currentTab) {
        if (state.currentTab == 0 && state.isFinished && validImageUrls.isEmpty()) {
            // Восстанавливаем из state если есть данные
            if (state.resultUrls.isNotEmpty()) {
                validImageUrls = state.resultUrls
            }
        }
    }




    fun tryPasteFromClipboard() {
        if (state.url.isEmpty() && !state.isWorking && !state.isFinished) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString() ?: ""
                if (text.contains("tiktok.com")) {
                    state.url = text.trim().trimEnd('/')
                }
            }
        }
    }

    fun isValidTikTokUrl(url: String): Boolean {
        return try {
            val patterns = listOf(
                Regex("""https?://(?:www\.|vm\.|vt\.)?tiktok\.com/.+"""),
                Regex("""https?://tiktok\.com/t/.+"""),
                Regex("""https?://m\.tiktok\.com/.+"""),
                Regex("""https?://www\.tiktok\.com/@.+"""),
                Regex("""https?://www\.tiktok\.com/.+/video/\d+"""),
                Regex("""https?://www\.tiktok\.com/.+/photo/\d+""")
            )

            patterns.any { it.matches(url) }
        } catch (e: Exception) {
            false
        }
    }

    // Основная функция парсинга (вынесена отдельно)
     fun startActualParsing(url: String) {
        state.isWorking = true
        state.isFinished = false
        state.resultUrls = emptyList()
        state.processedCount = 0
        state.foundCount = 0
        state.scrollState = 0
        state.clearCache()
        validImageUrls = emptyList()
        selectedUrls = emptySet()
        isSelectionMode = false
        showNothingSelectedDialog = false
        checkedUrlsCache = emptySet()

        pollingJob?.cancel()

        pollingJob = scope.launch {
            try {
                // Устанавливаем таймаут для всего процесса парсинга
                withTimeoutOrNull(180000) { // 3 минуты общий таймаут
                    val response = withContext(Dispatchers.IO) {
                        try {
                            // Таймаут для запроса к API
                            withTimeout(15000) {
                                api.startParse(mapOf("url" to url))
                            }
                        } catch (e: TimeoutCancellationException) {
                            Log.e("API", "Таймаут запроса к FastAPI серверу")
                            throw Exception(context.getString(R.string.error_api_timeout))
                        } catch (e: Exception) {
                            Log.e("API", "Ошибка запроса к API: ${e.message}")
                            throw Exception(context.getString(R.string.error_api_request))
                        }
                    }

                    val taskId = response["task_id"] ?: throw Exception(context.getString(R.string.error_no_task_id))

                    Log.d("Parser", "Начали парсинг, task_id: $taskId")

                    // Периодический опрос статуса
                    while (state.isWorking && isActive) {
                        val res = try {
                            withContext(Dispatchers.IO) {
                                // Таймаут для каждого запроса статуса
                                withTimeout(10000) {
                                    api.getStatus(taskId)
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            Log.w("Parser", "Таймаут запроса статуса")
                            continue // Пропускаем эту итерацию, пробуем снова
                        }

                        val status = res["status"] as? String
                        val processed = (res["processed_comments"] as? Number)?.toInt() ?: 0
                        val urls = (res["urls"] as? List<*>)?.map { it.toString() } ?: emptyList()

                        // Фильтруем пустые/некорректные URL
                        val validUrls = urls.filter {
                            it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://"))
                        }.distinct()

                        // Сохраняем ВСЕ найденные URL
                        state.resultUrls = validUrls
                        state.processedCount = processed

                        // Проверяем только НОВЫЕ URL
                        val newUrls = validUrls.filter { it !in checkedUrlsCache }

                        if (newUrls.isNotEmpty()) {
                            checkedUrlsCache = checkedUrlsCache + newUrls

                            // Проверяем новые URL асинхронно с таймаутом
                            val chunkResults = newUrls.chunked(5).map { chunk ->
                                async(Dispatchers.IO) {
                                    chunk.filter { imageUrl ->
                                        try {
                                            withTimeout(5000) {
                                                val connection = URL(imageUrl).openConnection() as HttpURLConnection
                                                connection.requestMethod = "HEAD"
                                                connection.connectTimeout = 2000
                                                connection.readTimeout = 2000
                                                connection.connect()

                                                val contentType = connection.contentType ?: ""
                                                val contentLength = connection.contentLength

                                                connection.disconnect()

                                                contentType.startsWith("image/") && contentLength > 100
                                            }
                                        } catch (e: TimeoutCancellationException) {
                                            Log.w("ImageCheck", "Таймаут проверки изображения: $imageUrl")
                                            false
                                        } catch (e: Exception) {
                                            // Если HEAD не работает, пробуем GET первых байтов
                                            try {
                                                withTimeout(5000) {
                                                    val connection = URL(imageUrl).openConnection() as HttpURLConnection
                                                    connection.connectTimeout = 3000
                                                    connection.readTimeout = 3000
                                                    connection.connect()

                                                    val contentType = connection.contentType ?: ""
                                                    val input = connection.inputStream
                                                    val buffer = ByteArray(128)
                                                    val bytesRead = input.read(buffer)
                                                    input.close()
                                                    connection.disconnect()

                                                    contentType.startsWith("image/") && bytesRead > 0
                                                }
                                            } catch (e2: Exception) {
                                                false
                                            }
                                        }
                                    }
                                }
                            }

                            // Собираем все результаты
                            chunkResults.forEach { deferred ->
                                val validChunk = deferred.await()
                                if (validChunk.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        validImageUrls = validImageUrls + validChunk
                                        state.foundCount = validImageUrls.size
                                    }
                                }
                            }
                        }

                        if (status == "completed") {
                            // Финальная проверка всех оставшихся картинок
                            val finalUrls = validUrls.filter { it !in checkedUrlsCache }
                            if (finalUrls.isNotEmpty()) {
                                val finalValidUrls = finalUrls.filter { imageUrl ->
                                    try {
                                        withTimeout(7000) {
                                            val connection = URL(imageUrl).openConnection() as HttpURLConnection
                                            connection.requestMethod = "HEAD"
                                            connection.connectTimeout = 3000
                                            connection.readTimeout = 3000
                                            connection.connect()

                                            val contentType = connection.contentType ?: ""
                                            val contentLength = connection.contentLength

                                            connection.disconnect()

                                            contentType.startsWith("image/") && contentLength > 100
                                        }
                                    } catch (e: Exception) {
                                        false
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    validImageUrls = validImageUrls + finalValidUrls
                                    state.foundCount = validImageUrls.size
                                }
                            }

                            withContext(Dispatchers.Main) {
                                state.isWorking = false
                                state.isFinished = true
                                state.url = ""
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.parse_completed, state.foundCount),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            break
                        }

                        // Если статус "failed" или "error"
                        if (status == "failed" || status == "error") {
                            withContext(Dispatchers.Main) {
                                state.isWorking = false
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.parse_failed),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            break
                        }

                        delay(1000)
                    }
                } ?: run {
                    // Сработал общий таймаут
                    withContext(Dispatchers.Main) {
                        state.isWorking = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.error_parse_timeout),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                state.isWorking = false
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        e.message ?: context.getString(R.string.error_download),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun startParsing(url: String) {
        if (url.isBlank()) {
            Toast.makeText(context, context.getString(R.string.error_empty_link), Toast.LENGTH_SHORT).show()
            return
        }

        // Проверка формата URL
        val trimmedUrl = url.trim()
        if (!isValidTikTokUrl(trimmedUrl)) {
            Toast.makeText(context, context.getString(R.string.error_invalid_tiktok_url), Toast.LENGTH_LONG).show()
            return
        }

        // Показываем прогресс сразу
        scope.launch(Dispatchers.Main) {
            // Сразу показываем, что начали проверку
            Toast.makeText(context, context.getString(R.string.checking_connections), Toast.LENGTH_SHORT).show()

            // Запускаем проверки через NetworkChecker
            val result = networkChecker.performAllChecks(trimmedUrl)

            when (result) {
                NetworkCheckResult.SUCCESS -> {
                    // Все проверки пройдены, начинаем парсинг
                    startActualParsing(trimmedUrl)
                }
                is NetworkCheckResult.FAILURE -> {
                    // Показываем конкретную ошибку
                    Toast.makeText(context, context.getString(result.messageRes), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Функция для проверки формата TikTok URL


    fun executeDownload(isZip: Boolean) {
        val urlsToProcess = when {
            // Если в режиме выделения и есть выбранные
            isSelectionMode && selectedUrls.isNotEmpty() -> selectedUrls.toList()
            // Если в режиме выделения, но ничего не выбрано - скачиваем все
            isSelectionMode -> validImageUrls
            // Если не в режиме выделения - скачиваем все
            else -> validImageUrls
        }

        // Немедленное выполнение если есть что скачивать
        if (urlsToProcess.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.error_download), Toast.LENGTH_SHORT).show()
            return
        }

        if (isZip) {
            state.isZipOperation = true
            state.currentDownloadJob?.cancel()
            state.currentDownloadJob = scope.launch {
                downloadAllAsZip(context, urlsToProcess, state) { state.downloadProgress = it }
            }
        } else {
            state.isZipOperation = false
            state.currentDownloadJob?.cancel()
            state.currentDownloadJob = scope.launch {
                saveAll(context, urlsToProcess, state) { state.downloadProgress = it }
            }
        }
    }

    LaunchedEffect(Unit) { tryPasteFromClipboard() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tryPasteFromClipboard()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pollingJob?.cancel()
        }
    }

    // ВАЖНОЕ ИЗМЕНЕНИЕ ДЛЯ COIL 3.x: Обертка для кастомного ImageLoader

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                if (validImageUrls.isEmpty() && !state.isWorking && !state.isFinished) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        OutlinedTextField(
                            value = state.url,
                            onValueChange = { state.url = it },
                            label = { Text(stringResource(R.string.input_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            checkClipboard(context, state)
                                        }
                                    },
                                    enabled = !state.isWorking && !state.isFinished
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_paste),
                                        stringResource(R.string.paste_from_clipboard),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )

                        Spacer(Modifier.height(16.dp))

                        Button(
                            onClick = { startParsing(state.url) },
                            enabled = state.url.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text(stringResource(R.string.btn_start))
                        }
                    }
                } else {
                    // Показываем статистику
                    Column {
                        // Заменяем: "Проверено комментариев: X"
                        Text(
                            stringResource(R.string.parse_scanning_comments, state.processedCount),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        // Заменяем: "Найдено картинок: X" (во время поиска)
                        if (state.isWorking) {
                            Text(
                                stringResource(R.string.parse_found_so_far, state.foundCount),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Если поиск завершен, показываем финальное сообщение
                        if (state.isFinished && state.foundCount > 0) {
                            Text(
                                stringResource(R.string.parse_result_ready, state.foundCount),
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        if (state.isWorking && state.foundCount == 0) {
                            Text(
                                stringResource(R.string.check_pics),
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    if (state.isWorking) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f).padding(top = 8.dp),
                        state = lazyGridState
                    ) {
                        items(validImageUrls) { url ->
                            val isSelected = selectedUrls.contains(url)
                            Box(
                                Modifier
                                    .padding(2.dp)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (isSelectionMode) {
                                            selectedUrls = if (isSelected) selectedUrls - url else selectedUrls + url
                                        } else {
                                            // Логируем подробную информацию о конкретной картинке
                                            Log.d("ImageClick", "🖱️ Tapped on image:")
                                            Log.d("ImageClick", "   - URL: $url")
                                            Log.d("ImageClick", "   - Is valid in list: ${validImageUrls.contains(url)}")
                                            Log.d("ImageClick", "   - Is selected: $isSelected")
                                            Log.d("ImageClick", "   - Index in list: ${validImageUrls.indexOf(url)}")
                                            Log.d("ImageClick", "   - Total valid images: ${validImageUrls.size}")

                                            // ───────────────────────────────────────────────
                                            // Определяем формат с помощью нашей функции (без байт → используем эвристику)
                                            // ───────────────────────────────────────────────
                                            val fakeContentType = ""  // Здесь нет реального заголовка на этапе клика

                                            val (estimatedMime, estimatedExt) = when {
                                                // Самый частый случай TikTok комментариев в 2025–2026
                                                url.contains("~tplv-") && url.endsWith(".image") -> {
                                                    Log.d("ImageClick", "   - Detected TikTok comment-style URL → assuming JPEG")
                                                    "image/jpeg" to ".jpg"
                                                }

                                                // Другие популярные TikTok/CDN паттерны (можно расширять)
                                                url.contains("tiktokcdn") && url.contains("image") -> "image/jpeg" to ".jpg"

                                                else -> {
                                                    // Если вдруг обычная ссылка с расширением — пробуем определить по ней
                                                    val extFromUrl = try {
                                                        val path = url.substringAfterLast(".")
                                                        if (path.length <= 10 && !path.contains("/") && !path.contains("?")) path.lowercase() else "unknown"
                                                    } catch (e: Exception) {
                                                        "unknown"
                                                    }

                                                    val mimeByExt = when (extFromUrl) {
                                                        "jpg", "jpeg", "jfif" -> "image/jpeg"
                                                        "png" -> "image/png"
                                                        "gif" -> "image/gif"
                                                        "webp" -> "image/webp"
                                                        "bmp" -> "image/bmp"
                                                        "heic", "heif" -> "image/heic"
                                                        else -> "unknown"
                                                    }

                                                    mimeByExt to if (extFromUrl != "unknown") ".$extFromUrl" else ".unknown"
                                                }
                                            }
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    val connection = URL(url).openConnection() as HttpURLConnection
                                                    connection.requestMethod = "HEAD"
                                                    connection.connectTimeout = 5000
                                                    connection.readTimeout = 5000

                                                    // Те же заголовки, что и в AsyncImage
                                                    connection.setRequestProperty("Referer", "https://www.tiktok.com/")
                                                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36")

                                                    val responseCode = connection.responseCode
                                                    val contentType = connection.contentType ?: "unknown"
                                                    val contentLength = connection.contentLength

                                                    connection.disconnect()

                                                    withContext(Dispatchers.Main) {
                                                        Log.d("ImageDebug", "HEAD check for $url:")
                                                        Log.d("ImageDebug", "   → Status: $responseCode")
                                                        Log.d("ImageDebug", "   → Content-Type: $contentType")
                                                        Log.d("ImageDebug", "   → Content-Length: $contentLength bytes")

                                                        // Можно показать Toast для отладки
                                                        // Toast.makeText(context, "Status: $responseCode", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        Log.e("ImageDebug", "HEAD check failed for $url: ${e.message}")
                                                    }
                                                }
                                            }

                                            val extension = estimatedExt.removePrefix(".")
                                            val mimeType = estimatedMime

                                            Log.d("ImageClick", "   - Estimated Extension: .$extension (via determine logic)")
                                            Log.d("ImageClick", "   - Estimated MIME type: $mimeType")

                                            // Анализируем тип картинки по расширению (для красоты в логах)
                                            val imageType = when (extension) {
                                                "jpg", "jpeg", "jfif" -> "JPEG"
                                                "png" -> "PNG"
                                                "gif" -> "GIF"
                                                "webp" -> "WebP"
                                                "bmp" -> "BMP"
                                                "heic", "heif" -> "HEIC/HEIF"
                                                "tiff", "tif" -> "TIFF"
                                                "ico" -> "ICO"
                                                "svg" -> "SVG"
                                                else -> "Unknown/Other (most likely JPEG from TikTok)"
                                            }
                                            Log.d("ImageClick", "   - Image type: $imageType")

                                            // ───────────────────────────────────────────────
                                            // Всё остальное остаётся без изменений
                                            // ───────────────────────────────────────────────

                                            val fileName = try {
                                                val pathPart = url.split("?").firstOrNull() ?: url
                                                val segments = pathPart.split("/")
                                                if (segments.isNotEmpty()) segments.last() else "unknown"
                                            } catch (e: Exception) {
                                                "unknown"
                                            }
                                            Log.d("ImageClick", "   - File name: $fileName")

                                            val hasSizeParam = url.contains("?") && (url.contains("size=") ||
                                                    url.contains("width=") || url.contains("height="))
                                            Log.d("ImageClick", "   - Has size parameters: $hasSizeParam")

                                            val isCdnUrl = url.contains("cdn.") || url.contains("cloudfront.") ||
                                                    url.contains("akamai.") || url.contains("cloudinary.") ||
                                                    url.contains("img.") || url.contains("images.")
                                            Log.d("ImageClick", "   - Is CDN URL: $isCdnUrl")

                                            val domain = try {
                                                java.net.URL(url).host
                                            } catch (e: Exception) {
                                                "unknown"
                                            }
                                            Log.d("ImageClick", "   - Domain: $domain")

                                            val protocol = if (url.startsWith("https://")) "HTTPS" else "HTTP"
                                            Log.d("ImageClick", "   - Protocol: $protocol")

                                            val hasQualityParam = url.contains("quality=") || url.contains("q=") ||
                                                    url.contains("compression=")
                                            Log.d("ImageClick", "   - Has quality parameters: $hasQualityParam")

                                            Log.d("ImageClick", "   - URL length: ${url.length} chars")

                                            val hasVersion = url.contains("v=") || url.contains("version=") ||
                                                    url.contains("ver=") || url.contains("_v") ||
                                                    url.contains(Regex(".*v[0-9]+.*"))
                                            Log.d("ImageClick", "   - Has version: $hasVersion")

                                            val isStatic = !url.contains("?") || (url.contains("?") && !url.contains("&"))
                                            Log.d("ImageClick", "   - Is static URL: $isStatic")

                                            val isCacheable = extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
                                            Log.d("ImageClick", "   - Is cacheable format: $isCacheable")

                                            val hasNumericId = fileName.contains(Regex("\\d{5,}"))
                                            Log.d("ImageClick", "   - Has numeric ID in filename: $hasNumericId")

                                            val hasAuthToken = url.contains("token=") || url.contains("auth=") ||
                                                    url.contains("access_token=") || url.contains("api_key=")
                                            Log.d("ImageClick", "   - Has auth token: $hasAuthToken")

                                            Log.d("ImageClick", "=".repeat(50))
                                        }
                                    }
                            ) {
                                // ВАЖНОЕ ИЗМЕНЕНИЕ ДЛЯ COIL 3.x: AsyncImage
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(url)
                                        .crossfade(true)
                                        .placeholder(R.drawable.ic_image_placeholder)
                                        .error(R.drawable.ic_broken_image)

                                        // ← Заголовки — работает в 3.3.0 после добавления coil-network-okhttp
                                        .httpHeaders(
                                            NetworkHeaders.Builder()
                                                .set("Referer", "https://www.tiktok.com/")
                                                .set(
                                                    "User-Agent",
                                                    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36"
                                                )
                                                // Опционально, если нужно:
                                                // .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                                                // .set("Accept-Language", "en-US,en;q=0.9")
                                                .build()
                                        )

                                        .listener(
                                            onStart = { Log.d("CoilLoad", "Started loading: $url") },
                                            onSuccess = { request, result ->
                                                scope.launch(Dispatchers.IO) {
                                                    try {
                                                        // Получаем ImageLoader
                                                        val imageLoader = (context as MainActivity).imageLoader

                                                        // Загружаем изображение как Bitmap
                                                        val bitmap = imageLoader.execute(
                                                            ImageRequest.Builder(context)
                                                                .data(url)
                                                                .allowHardware(false) // Отключаем hardware bitmap для возможности конвертации
                                                                .build()
                                                        ).image?.toBitmap()

                                                        // Конвертируем в ByteArray
                                                        val byteArray = if (bitmap != null) {
                                                            val outputStream = ByteArrayOutputStream()
                                                            val format = when {
                                                                url.contains(".png", ignoreCase = true) -> Bitmap.CompressFormat.PNG
                                                                url.contains(".webp", ignoreCase = true) -> Bitmap.CompressFormat.WEBP
                                                                else -> Bitmap.CompressFormat.JPEG
                                                            }
                                                            bitmap.compress(format, 90, outputStream)
                                                            outputStream.toByteArray()
                                                        } else {
                                                            ByteArray(0)
                                                        }

                                                        if (byteArray.isNotEmpty()) {
                                                            val MAX_CACHE_SIZE = 1000
                                                            if (state.cachedImages.size >= MAX_CACHE_SIZE) {
                                                                val oldest = state.cachedImages.entries
                                                                    .minByOrNull { it.value.timestamp }
                                                                if (oldest != null) {
                                                                    state.cachedImages.remove(oldest.key)
                                                                    Log.d("Cache", "Удалён старый кэш: ${oldest.key}")
                                                                }
                                                            }
                                                            state.cachedImages[url] = CachedImage(
                                                                url = url,
                                                                data = byteArray,
                                                                mimeType = determineMimeType(byteArray, url)
                                                            )
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e("ImageCache", "Ошибка", e)
                                                    }
                                                }

                                            },
                                            onError = { _, result ->
                                                val errorMsg = result.throwable.message ?: "Unknown error"
                                                Log.e("CoilError", "Failed to load $url → $errorMsg")
                                                // Coil не даёт HTTP-код напрямую здесь (это limitation библиотеки)
                                            }
                                        )
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    imageLoader = (LocalContext.current as MainActivity).imageLoader
                                )

                                // Показываем кнопку скачивания только если не в режиме выбора
                                if (!isSelectionMode) {
                                    IconButton(
                                        onClick = { scope.launch { saveImg(context, url) } },
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                            .size(32.dp).background(Color.Black.copy(0.5f), CircleShape)
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_download),
                                            null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                // Показываем выделение если в режиме выбора
                                if (isSelected) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.primary.copy(0.5f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_check),
                                            null,
                                            tint = Color.White,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.isFinished) Spacer(Modifier.height(90.dp))
                }
            }

            if (state.isFinished) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    tonalElevation = 8.dp,
                    shadowElevation = 16.dp
                ) {
                    if (isSelectionMode) {
                        // Режим выделения - показываем выбранное количество
                        Column(
                            Modifier.padding(12.dp).navigationBarsPadding(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Показываем сколько выбрано
                            Text(
                                text = stringResource(R.string.selection_count, selectedUrls.size),
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                                    .padding(bottom = 8.dp),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )

                            // Показываем уведомление если ничего не выбрано
                            if (selectedUrls.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.nothing_selected_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Normal,
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .padding(bottom = 8.dp)
                                )
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (selectedUrls.isNotEmpty()) {
                                            state.showDownloadAllDialog = true
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    enabled = selectedUrls.isNotEmpty() // ← Блокируем если ничего не выбрано
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_download),
                                        null,
                                        Modifier.size(16.dp)
                                    )
                                    Text(stringResource(R.string.btn_download), fontSize = 14.sp)
                                }
                                Button(
                                    onClick = {
                                        if (selectedUrls.isNotEmpty()) {
                                            state.showZipDialog = true
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    enabled = selectedUrls.isNotEmpty() // ← Блокируем если ничего не выбрано
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_archive),
                                        null,
                                        Modifier.size(16.dp)
                                    )
                                    Text(stringResource(R.string.btn_zip_short), fontSize = 14.sp)
                                }
                            }
                            // Кнопка отмены выделения
                            Button(
                                onClick = {
                                    isSelectionMode = false
                                    selectedUrls = emptySet() // Снимаем все выделения
                                },
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Text(stringResource(R.string.btn_cancel), fontSize = 14.sp)
                            }
                        }
                    } else {
                        // Обычный режим (не выделение)
                        Row(
                            Modifier.padding(12.dp).navigationBarsPadding(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            // Включаем режим выделения (НЕ выделяем все автоматически)
                                            isSelectionMode = true
                                            selectedUrls = emptySet() // Начинаем с пустого выбора
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp)
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_select_all),
                                            null,
                                            Modifier.size(18.dp)
                                        )
                                        Text(
                                            stringResource(R.string.btn_selection_mode),
                                            fontSize = 14.sp
                                        )
                                    }
                                    Button(
                                        onClick = { state.showDownloadAllDialog = true },
                                        modifier = Modifier.weight(1f).height(48.dp)
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_download),
                                            null,
                                            Modifier.size(18.dp)
                                        )
                                        Text(
                                            stringResource(R.string.btn_download_all),
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { state.showZipDialog = true },
                                        modifier = Modifier.weight(1f).height(48.dp)
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_archive),
                                            null,
                                            Modifier.size(18.dp)
                                        )
                                        Text(stringResource(R.string.btn_zip), fontSize = 14.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            state.resultUrls = emptyList()
                                            state.isFinished = false
                                            state.url = ""
                                            state.processedCount = 0
                                            state.foundCount = 0
                                            state.scrollState = 0
                                            pollingJob?.cancel()
                                            validImageUrls = emptyList()
                                            selectedUrls = emptySet()
                                            isSelectionMode = false
                                            checkedUrlsCache = emptySet()
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_clear),
                                            null,
                                            Modifier.size(18.dp)
                                        )
                                        Text(stringResource(R.string.btn_cancel), fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.showDownloadAllDialog) {
            AlertDialog(
                onDismissRequest = { state.showDownloadAllDialog = false },
                title = {
                    val titleRes = when {
                        isSelectionMode && selectedUrls.isNotEmpty() -> R.string.dialog_download_selected_title
                        isSelectionMode -> R.string.dialog_nothing_selected_title_selection
                        else -> R.string.dialog_download_title
                    }
                    Text(stringResource(titleRes))
                },
                text = {
                    val (textRes, count) = when {
                        isSelectionMode && selectedUrls.isNotEmpty() -> {
                            Pair(R.string.dialog_download_selected_text, selectedUrls.size)
                        }
                        isSelectionMode -> {
                            Pair(R.string.dialog_nothing_selected_text_selection, validImageUrls.size)
                        }
                        else -> {
                            Pair(R.string.dialog_download_text, validImageUrls.size)
                        }
                    }
                    Text(stringResource(textRes, count))
                },
                confirmButton = {
                    Button(onClick = {
                        state.showDownloadAllDialog = false
                        executeDownload(isZip = false)
                    }) { Text(stringResource(R.string.confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { state.showDownloadAllDialog = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }

        if (state.showZipDialog) {
            AlertDialog(
                onDismissRequest = { state.showZipDialog = false },
                title = {
                    val titleRes = when {
                        isSelectionMode && selectedUrls.isNotEmpty() -> R.string.dialog_zip_selected_title
                        isSelectionMode -> R.string.dialog_nothing_selected_title_selection
                        else -> R.string.dialog_zip_title
                    }
                    Text(stringResource(titleRes))
                },
                text = {
                    val (textRes, count) = when {
                        isSelectionMode && selectedUrls.isNotEmpty() -> {
                            Pair(R.string.dialog_zip_selected_text, selectedUrls.size)
                        }
                        isSelectionMode -> {
                            Pair(R.string.dialog_nothing_selected_zip_text_selection, validImageUrls.size)
                        }
                        else -> {
                            Pair(R.string.dialog_zip_text, validImageUrls.size)
                        }
                    }
                    Text(stringResource(textRes, count))
                },
                confirmButton = {
                    Button(onClick = {
                        state.showZipDialog = false
                        executeDownload(isZip = true)
                    }) { Text(stringResource(R.string.confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { state.showZipDialog = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }

        if (showNothingSelectedDialog) {
            AlertDialog(
                onDismissRequest = {
                    showNothingSelectedDialog = false
                    pendingAction = null
                },
                title = { Text(stringResource(R.string.dialog_nothing_selected_title)) },
                text = { Text(stringResource(R.string.dialog_nothing_selected_text)) },
                confirmButton = {
                    Button(onClick = {
                        showNothingSelectedDialog = false
                        pendingAction?.invoke()
                        pendingAction = null
                    }) { Text(stringResource(R.string.btn_download_all_anyway)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showNothingSelectedDialog = false
                        pendingAction = null
                    }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }

        if (state.downloadProgress >= 0) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.processing_label)) },
                text = {
                    Column {
                        LinearProgressIndicator(progress = { state.downloadProgress }, modifier = Modifier.fillMaxWidth())
                        Text("${(state.downloadProgress * 100).toInt()}%", modifier = Modifier.align(Alignment.End))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        state.currentDownloadJob?.cancel()
                        state.downloadProgress = -1f
                        state.isDownloading = false
                        val msg = if (state.isZipOperation) context.getString(R.string.zip_cancelled) else context.getString(R.string.download_cancelled)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.btn_cancel)) }
                }
            )
        }

}

@Composable
fun GalleryScreen(
    isSelectionMode: Boolean,
    onSelectionChange: (Int, Long) -> Unit,
    onTotalItemsChanged: (Int) -> Unit,
    onDeleteRequest: Boolean,
    onSelectAllRequest: Boolean,
    onOperationFinished: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groupedItems by remember { mutableStateOf(listOf<GalleryGroup>()) }
    var selectedItems by remember { mutableStateOf(setOf<GalleryItem>()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var totalGallerySize by remember { mutableLongStateOf(0L) }
    var sortOrder by remember { mutableStateOf(GallerySortOrder.NEWEST_FIRST) }

    fun load() {
        scope.launch {
            groupedItems = loadGalleryGrouped(context, sortOrder)
            val totalItems = groupedItems.sumOf { it.items.size }
            onTotalItemsChanged(totalItems)

            // Получаем общий размер галереи используя вашу функцию
            totalGallerySize = getGallerySizeBytes(context)
        }
    }

    // Первоначальная загрузка
    LaunchedEffect(Unit) {
        load()
    }

    // Обновляем данные при изменении порядка сортировки
    LaunchedEffect(sortOrder) {
        load()
    }

    // Подсчет размера выбранных и уведомление родителя
    LaunchedEffect(selectedItems) {
        val uris = selectedItems.map { it.uri.toString() }
        // Используем вашу функцию из SizeUtil.kt
        val totalSize = getSelectedSizeBytes(context, uris)
        onSelectionChange(selectedItems.size, totalSize)
    }

    // Обработка запроса на удаление
    LaunchedEffect(onDeleteRequest) {
        if (onDeleteRequest) {
            showDeleteDialog = true
        }
    }

    // Обработка запроса "Выбрать все" или "Снять все"
    LaunchedEffect(onSelectAllRequest) {
        if (onSelectAllRequest) {
            val allItems = groupedItems.flatMap { it.items }
            if (selectedItems.size == allItems.size) {
                // Если уже все выбраны - снимаем выделение
                selectedItems = emptySet()
            } else {
                // Иначе выбираем все
                selectedItems = allItems.toSet()
            }
            onOperationFinished(false)
        }
    }

    // Сброс выделения при выходе из режима выбора
    LaunchedEffect(isSelectionMode) {
        if (!isSelectionMode) {
            selectedItems = emptySet()
        }
    }

    // Функция для массового шеринга
    fun shareSelectedImages() {
        if (selectedItems.isEmpty()) return

        try {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                val uris = ArrayList<Uri>()
                selectedItems.forEach { item ->
                    uris.add(item.uri)
                }
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_multiple_title)))
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.share_error), Toast.LENGTH_SHORT).show()
        }
    }

    Column {
        // Статистика всего хранилища и кнопка сортировки
        if (groupedItems.isNotEmpty() && !isSelectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    val totalItems = groupedItems.sumOf { it.items.size }
                    Text(
                        text = stringResource(R.string.gallery_total_stats, totalItems, formatFileSize(totalGallerySize)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Подсказка текущей сортировки
                    Text(
                        text = when (sortOrder) {
                            GallerySortOrder.NEWEST_FIRST -> stringResource(R.string.sort_newest_first)
                            GallerySortOrder.OLDEST_FIRST -> stringResource(R.string.sort_oldest_first)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // Кнопка сортировки
                IconButton(
                    onClick = {
                        sortOrder = when (sortOrder) {
                            GallerySortOrder.NEWEST_FIRST -> GallerySortOrder.OLDEST_FIRST
                            GallerySortOrder.OLDEST_FIRST -> GallerySortOrder.NEWEST_FIRST
                        }
                        // Сбрасываем выделение при смене сортировки
                        selectedItems = emptySet()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_sort_arrows),
                        contentDescription = stringResource(R.string.accessibility_sort_button),
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(if (sortOrder == GallerySortOrder.NEWEST_FIRST) 0f else 180f),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (groupedItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.gallery_empty), color = Color.Gray, fontSize = 20.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {
                groupedItems.forEach { group ->
                    // Заголовок с датой
                    item {
                        Text(
                            text = group.dateLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    // Сетка фотографий для этой даты
                    items(
                        items = group.items.chunked(3),
                        key = { chunk -> chunk.hashCode() }
                    ) { rowItems ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rowItems.forEach { item ->
                                val isSelected = selectedItems.contains(item)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .padding(2.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            if (isSelectionMode) {
                                                selectedItems = if (isSelected) selectedItems - item else selectedItems + item
                                            }
                                        }
                                ) {
                                    AsyncImage(
                                        model = item.uri,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    if (!isSelectionMode) {
                                        IconButton(
                                            onClick = { shareImage(context, item.uri) },
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(4.dp)
                                                .size(28.dp)
                                                .background(Color.Black.copy(0.4f), CircleShape)
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_share),
                                                null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    if (isSelected) {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .background(MaterialTheme.colorScheme.primary.copy(0.5f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_check),
                                                null,
                                                tint = Color.White,
                                                modifier = Modifier.size(36.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Добавляем пустые места для выравнивания
                            repeat(3 - rowItems.size) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .padding(2.dp)
                                )
                            }
                        }
                    }

                    // Разделитель между группами (кроме последней)
                    if (group != groupedItems.last()) {
                        item {
                            Divider(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // Более информативный Floating Action Button с меню
    if (isSelectionMode && selectedItems.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 88.dp, end = 16.dp)
                .wrapContentSize(Alignment.BottomEnd)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Кнопка шеринга
                FloatingActionButton(
                    onClick = { showShareDialog = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(painterResource(R.drawable.ic_share), null, Modifier.size(20.dp))
                        Text(
                            stringResource(R.string.btn_share_selected, selectedItems.size),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Кнопка удаления
                FloatingActionButton(
                    onClick = { showDeleteDialog = true },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(20.dp))
                        Text(
                            stringResource(R.string.btn_delete_selected, selectedItems.size),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        val totalSizeToDelete = selectedItems.sumOf { it.size }
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                onOperationFinished(false)
            },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dialog_delete_text,
                        selectedItems.size,
                        formatFileSize(totalSizeToDelete)
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            selectedItems.forEach { context.contentResolver.delete(it.uri, null, null) }
                            withContext(Dispatchers.Main) {
                                load() // Перезагружаем список
                                showDeleteDialog = false
                                onOperationFinished(true) // Deleted successfully
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_deleted),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.btn_confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onOperationFinished(false)
                }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (showShareDialog) {
        AlertDialog(
            onDismissRequest = {
                showShareDialog = false
                onOperationFinished(false)
            },
            title = { Text(stringResource(R.string.dialog_share_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dialog_share_text,
                        selectedItems.size
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showShareDialog = false
                        shareSelectedImages()
                        onOperationFinished(true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text(stringResource(R.string.btn_confirm_share))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showShareDialog = false
                    onOperationFinished(false)
                }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
fun ArchivesScreen(
    isSelectionMode: Boolean,
    onSelectionChange: (Int, Long) -> Unit,
    onTotalItemsChanged: (Int) -> Unit,
    onDeleteRequest: Boolean,
    onSelectAllRequest: Boolean,
    onOperationFinished: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(listOf<ArchiveItem>()) }
    var selectedItems by remember { mutableStateOf(setOf<ArchiveItem>()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    fun load() {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PicsTok")
        if (dir.exists()) {
            items = dir.listFiles { _, name -> name.endsWith(".zip") }
                ?.map { ArchiveItem(it) }
                ?.sortedByDescending { it.file.lastModified() } ?: emptyList()
        } else {
            items = emptyList()
        }
        // Уведомляем родителя об общем количестве
        onTotalItemsChanged(items.size)
    }

    LaunchedEffect(Unit) { load() }

    // Периодическое обновление списка
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) load()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Подсчет размера выбранных
    LaunchedEffect(selectedItems) {
        val totalSize = selectedItems.sumOf { it.file.length() }
        onSelectionChange(selectedItems.size, totalSize)
    }

    // Обработка запроса на удаление
    LaunchedEffect(onDeleteRequest) {
        if (onDeleteRequest) showDeleteDialog = true
    }

    // Обработка запроса "Выбрать все" или "Снять все"
    LaunchedEffect(onSelectAllRequest) {
        if (onSelectAllRequest) {
            if (selectedItems.size == items.size) {
                // Если уже все выбраны - снимаем выделение
                selectedItems = emptySet()
            } else {
                // Иначе выбираем все
                selectedItems = items.toSet()
            }
            onOperationFinished(false)
        }
    }

    // Сброс выделения при выходе из режима выбора
    LaunchedEffect(isSelectionMode) {
        if (!isSelectionMode) selectedItems = emptySet()
    }

    Column {
        // Статистика всех архивов
        if (items.isNotEmpty() && !isSelectionMode) {
            val totalSize = getArchivesSizeBytes()
            Text(
                text = stringResource(R.string.gallery_total_stats, items.size, formatFileSize(totalSize)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).align(Alignment.CenterHorizontally)
            )
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.archives_empty), color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(1),
                modifier = Modifier.weight(1f).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items) { item ->
                    val isSelected = selectedItems.contains(item)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .clickable {
                                if (isSelectionMode) {
                                    selectedItems = if (isSelected) selectedItems - item else selectedItems + item
                                } else {
                                    shareFile(context, item.file)
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_archive),
                                null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.file.name, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 16.sp)
                                Text(formatFileSize(item.file.length()), style = MaterialTheme.typography.bodySmall)
                            }
                            if (isSelected) {
                                Icon(painterResource(R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.primary)
                            } else {
                                IconButton(onClick = { shareFile(context, item.file) }) {
                                    Icon(painterResource(R.drawable.ic_share), null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        val totalSizeToDelete = selectedItems.sumOf { it.file.length() }
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                onOperationFinished(false)
            },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dialog_delete_text,
                        selectedItems.size,
                        formatFileSize(totalSizeToDelete)
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            selectedItems.forEach { it.file.delete() }
                            withContext(Dispatchers.Main) {
                                load() // Перезагружаем список
                                showDeleteDialog = false
                                onOperationFinished(true)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_deleted),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.btn_confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onOperationFinished(false)
                }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Composable
fun SettingsSheet(state: ParserState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity ?: return
    var coilCacheBytes by remember { mutableLongStateOf(0L) }
    var galleryBytes by remember { mutableLongStateOf(0L) }
    var archivesBytes by remember { mutableLongStateOf(0L) }
    var storageInfo by remember { mutableStateOf(StorageInfo(0L, 0L)) }

    // Загрузка данных о хранилище
    LaunchedEffect(Unit) {
        coilCacheBytes = activity.imageLoader.diskCache?.size ?: 0L
        galleryBytes = getGallerySizeBytes(context)
        archivesBytes = getArchivesSizeBytes()
        storageInfo = getInternalStorageInfo()
    }

    // 1. ДАННЫЕ ДЛЯ ПРОГРЕСС-БАРА УСТРОЙСТВА
    val totalDeviceBytes = storageInfo.totalBytes
    val usedDeviceBytes = storageInfo.usedBytes
    val freeDeviceBytes = totalDeviceBytes - usedDeviceBytes

    val deviceUsedPercent = if (totalDeviceBytes > 0)
        usedDeviceBytes.toFloat() / totalDeviceBytes
    else 0f

    // 2. ДАННЫЕ ДЛЯ ПРОГРЕСС-БАРА ПРИЛОЖЕНИЯ
    val totalAppBytes = coilCacheBytes + galleryBytes + archivesBytes

    val coilPercent = if (totalAppBytes > 0)
        coilCacheBytes.toFloat() / totalAppBytes
    else 0f

    val galleryPercent = if (totalAppBytes > 0)
        galleryBytes.toFloat() / totalAppBytes
    else 0f

    val archivesPercent = if (totalAppBytes > 0)
        archivesBytes.toFloat() / totalAppBytes
    else 0f

    // 3. КАКУЮ ЧАСТЬ ВСЕГО УСТРОЙСТВА ЗАНИМАЕТ ПРИЛОЖЕНИЕ
    val appToDevicePercent = if (totalDeviceBytes > 0)
        totalAppBytes.toFloat() / totalDeviceBytes
    else 0f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(),
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true
        )
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            // ЗАГОЛОВОК
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 1. НАСТРОЙКИ ТЕМЫ
            Text(
                stringResource(R.string.theme_label),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )

            val themes = listOf(
                "System" to stringResource(R.string.theme_system),
                "Light" to stringResource(R.string.theme_light),
                "Dark" to stringResource(R.string.theme_dark)
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                themes.forEach { (key, label) ->
                    FilterChip(
                        selected = state.themeMode == key,
                        onClick = { state.themeMode = key },
                        label = { Text(label, fontSize = 16.sp) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 2. MATERIAL YOU
            val isAndroid12 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.material_you_label), fontSize = 16.sp)
                },
                supportingContent = {
                    Text(stringResource(R.string.material_you_desc), fontSize = 14.sp)
                },
                trailingContent = {
                    Switch(
                        checked = state.useMaterialYou,
                        onCheckedChange = { state.useMaterialYou = it },
                        enabled = isAndroid12
                    )
                }
            )

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(24.dp))

            // СЕКЦИЯ: ИСПОЛЬЗОВАНИЕ ХРАНИЛИЩА
            Text(
                stringResource(R.string.storage_usage_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // 1. ОБЩАЯ СТАТИСТИКА
            Text(
                stringResource(
                    R.string.storage_summary,
                    formatFileSize(totalAppBytes),
                    formatFileSize(totalDeviceBytes),
                    (appToDevicePercent * 100).toInt()
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // 2. ПРОГРЕСС-БАР ВСЕГО УСТРОЙСТВА
            Text(
                stringResource(R.string.storage_device_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                // Занятое место на устройстве
                if (deviceUsedPercent > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(deviceUsedPercent)
                            .clip(
                                RoundedCornerShape(
                                    topStart = 10.dp,
                                    bottomStart = 10.dp,
                                    topEnd = if (deviceUsedPercent >= 1f) 10.dp else 0.dp,
                                    bottomEnd = if (deviceUsedPercent >= 1f) 10.dp else 0.dp
                                )
                            )
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }

            // Легенда для устройства
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LegendItemSmall(
                    color = MaterialTheme.colorScheme.primary,
                    label = stringResource(R.string.storage_device_used),
                    size = usedDeviceBytes,
                    percent = (deviceUsedPercent * 100).toInt()
                )
                LegendItemSmall(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    label = stringResource(R.string.storage_device_free),
                    size = freeDeviceBytes,
                    percent = 100 - (deviceUsedPercent * 100).toInt()
                )
            }

            Spacer(Modifier.height(20.dp))

            // 3. ПРОГРЕСС-БАР ПРИЛОЖЕНИЯ
            Text(
                stringResource(R.string.storage_app_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (totalAppBytes > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Кэш Coil
                        if (coilPercent > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(coilPercent)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 10.dp,
                                            bottomStart = 10.dp,
                                            topEnd = if (coilPercent >= 1f) 10.dp else 0.dp,
                                            bottomEnd = if (coilPercent >= 1f) 10.dp else 0.dp
                                        )
                                    )
                                    .background(Color.Blue)
                            )
                        }

                        // Галерея
                        if (galleryPercent > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(galleryPercent)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 0.dp,
                                            bottomStart = 0.dp,
                                            topEnd = if ((coilPercent + galleryPercent) >= 1f) 10.dp else 0.dp,
                                            bottomEnd = if ((coilPercent + galleryPercent) >= 1f) 10.dp else 0.dp
                                        )
                                    )
                                    .background(Color.Green)
                            )
                        }

                        // Архивы
                        if (archivesPercent > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(archivesPercent)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 0.dp,
                                            bottomStart = 0.dp,
                                            topEnd = if ((coilPercent + galleryPercent + archivesPercent) >= 1f) 10.dp else 0.dp,
                                            bottomEnd = if ((coilPercent + galleryPercent + archivesPercent) >= 1f) 10.dp else 0.dp
                                        )
                                    )
                                    .background(Color.Magenta)
                            )
                        }
                    }
                }

                // Легенда для приложения
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    LegendItemSmall(
                        color = Color.Blue,
                        label = stringResource(R.string.storage_legend_cache),
                        size = coilCacheBytes,
                        percent = (coilPercent * 100).toInt()
                    )
                    LegendItemSmall(
                        color = Color.Green,
                        label = stringResource(R.string.storage_legend_gallery),
                        size = galleryBytes,
                        percent = (galleryPercent * 100).toInt()
                    )
                    LegendItemSmall(
                        color = Color.Magenta,
                        label = stringResource(R.string.storage_legend_archives),
                        size = archivesBytes,
                        percent = (archivesPercent * 100).toInt()
                    )
                }
            } else {
                Text(
                    stringResource(R.string.storage_app_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            // 4. ДЕТАЛЬНАЯ ТАБЛИЦА
            Text(
                stringResource(R.string.storage_details_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(12.dp)
            ) {
                // Устройство
                StorageDetailRow(
                    label = stringResource(R.string.storage_detail_device_total),
                    size = totalDeviceBytes
                )
                StorageDetailRow(
                    label = stringResource(R.string.storage_detail_device_used),
                    size = usedDeviceBytes,
                    indent = true
                )
                StorageDetailRow(
                    label = stringResource(R.string.storage_detail_device_free),
                    size = freeDeviceBytes,
                    indent = true
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Приложение
                StorageDetailRow(
                    label = stringResource(R.string.storage_detail_app_total),
                    size = totalAppBytes
                )
                StorageDetailRow(
                    label = stringResource(R.string.storage_legend_cache),
                    size = coilCacheBytes,
                    indent = true
                )
                StorageDetailRow(
                    label = stringResource(R.string.storage_legend_gallery),
                    size = galleryBytes,
                    indent = true
                )
                StorageDetailRow(
                    label = stringResource(R.string.storage_legend_archives),
                    size = archivesBytes,
                    indent = true
                )
            }

            Spacer(Modifier.height(24.dp))

            // 5. КНОПКА ОЧИСТКИ КЭША
            Button(
                onClick = {
                    activity.imageLoader.memoryCache?.clear()
                    activity.imageLoader.diskCache?.clear()
                    coilCacheBytes = 0L
                    Toast.makeText(
                        context,
                        context.getString(R.string.cache_cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                enabled = coilCacheBytes > 0,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(stringResource(R.string.btn_clear_cache), fontSize = 16.sp)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// Компактная легенда
@Composable
private fun LegendItemSmall(color: Color, label: String, size: Long, percent: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(100.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "$label ($percent%)",
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 12.sp
        )
        Text(
            formatFileSize(size),
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

// Детальная строка статистики
@Composable
private fun StorageDetailRow(label: String, size: Long, indent: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (indent) "   $label" else label,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            formatFileSize(size),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}



private fun isValidImage(bytes: ByteArray, url: String): Boolean {
    Log.d("PicsTok", "🔍 Checking image validity: ${bytes.size} bytes (URL: $url)")

    if (bytes.isEmpty()) return false

    try {
        val result = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> true  // JPEG
            bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> true  // PNG
            bytes.size >= 6 && (bytes.sliceArray(0..5).contentEquals(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)) ||
                    bytes.sliceArray(0..5).contentEquals(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))) -> true  // GIF
            bytes.size >= 12 && bytes.sliceArray(0..3).contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46)) &&
                    bytes.sliceArray(8..11).contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50)) -> true  // WebP
            bytes.size >= 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte() -> true  // BMP

            // HEIC/HEIF
            bytes.size >= 12 && bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
                    bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte() && (
                    (bytes[8] == 0x68.toByte() && bytes[9] == 0x65.toByte() && bytes[10] == 0x69.toByte() && bytes[11] == 0x63.toByte()) ||
                            (bytes[8] == 0x6D.toByte() && bytes[9] == 0x69.toByte() && bytes[10] == 0x66.toByte() && bytes[11] == 0x31.toByte())
                    ) -> {
                Log.d("PicsTok", "✅ HEIC/HEIF detected")
                true
            }

            else -> {
                val allZero = bytes.all { it == 0.toByte() }
                val hasData = bytes.any { it != 0.toByte() }
                if (!allZero && hasData) {
                    val hex = bytes.take(16).joinToString(" ") { "%02x".format(it) }
                    Log.w("PicsTok", "Unknown format but has data. First 16: $hex")
                    true
                } else false
            }
        }

        if (!result) {
            val hex = bytes.take(16).joinToString(" ") { "%02x".format(it) }
            Log.w("PicsTok", "Invalid image bytes: $hex  (size=${bytes.size})  URL=$url")
        }

        return result
    } catch (e: Exception) {
        Log.e("PicsTok", "Error in isValidImage for $url: ${e.message}")
        return false
    }
}

// --- Загрузка и шаринг (стандартные функции) ---

private fun determineImageFormat(bytes: ByteArray, contentType: String): Pair<String, String> {
    if (contentType.isNotBlank()) {
        when {
            contentType.startsWith("image/jpeg") -> return "image/jpeg" to ".jpg"
            contentType.startsWith("image/png")  -> return "image/png" to ".png"
            contentType.startsWith("image/gif")  -> return "image/gif" to ".gif"
            contentType.startsWith("image/webp") -> return "image/webp" to ".webp"
            contentType.startsWith("image/bmp")  -> return "image/bmp" to ".bmp"
            contentType.startsWith("image/heic") || contentType.startsWith("image/heif") ->
                return "image/heic" to ".heic"
            else -> Log.w("PicsTok", "Unknown Content-Type: $contentType → fallback to magic")
        }
    }

    if (bytes.size >= 12) {
        // JPEG
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte())
            return "image/jpeg" to ".jpg"

        // PNG
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()) return "image/png" to ".png"

        // GIF
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x38.toByte()) return "image/gif" to ".gif"

        // WebP
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x46.toByte() && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()) return "image/webp" to ".webp"

        // BMP
        if (bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) return "image/bmp" to ".bmp"

        // HEIC/HEIF (ftypheic или ftymif1)
        if (bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() && bytes[6] == 0x79.toByte() &&
            bytes[7] == 0x70.toByte() && (
                    (bytes[8] == 0x68.toByte() && bytes[9] == 0x65.toByte() && bytes[10] == 0x69.toByte() && bytes[11] == 0x63.toByte()) ||
                            (bytes[8] == 0x6D.toByte() && bytes[9] == 0x69.toByte() && bytes[10] == 0x66.toByte() && bytes[11] == 0x31.toByte())
                    )) return "image/heic" to ".heic"
    }

    Log.w("PicsTok", "Не удалось определить формат → fallback на jpeg")
    return "image/jpeg" to ".jpg"
}


private suspend fun saveAll(c: Context, urls: List<String>, state: ParserState, onProgress: (Float) -> Unit) {
    state.isDownloading = true
    withContext(Dispatchers.IO) {
        try {
            val validUrls = urls.distinct()
            var savedCount = 0
            var skippedCount = 0
            var errorCount = 0
            var invalidCount = 0

            validUrls.forEachIndexed { i, u ->
                if (state.isDownloading) {
                    val result = saveImgInternal(c, u, true)
                    when (result) {
                        1 -> savedCount++
                        2 -> skippedCount++
                        0 -> invalidCount++ // Битые картинки
                        else -> errorCount++
                    }
                    withContext(Dispatchers.Main) { onProgress((i + 1).toFloat() / validUrls.size) }
                }
            }

            withContext(Dispatchers.Main) {
                onProgress(-1f)
                val wasActive = state.isDownloading
                state.isDownloading = false

                if (wasActive) {
                    val message = buildString {
                        if (savedCount > 0) {
                            append(c.getString(R.string.toast_saved_count, savedCount))
                        }
                        if (skippedCount > 0) {
                            if (isNotEmpty()) append(" ")
                            append(c.getString(R.string.toast_skipped, skippedCount))
                        }
                        if (invalidCount > 0) {
                            if (isNotEmpty()) append(" ")
                            append(c.getString(R.string.zip_invalid_images, invalidCount))
                        }
                        if (errorCount > 0) {
                            if (isNotEmpty()) append(" ")
                            append(c.getString(R.string.toast_errors, errorCount))
                        }
                    }
                    Toast.makeText(c, message, Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onProgress(-1f)
                state.isDownloading = false
                Toast.makeText(c, c.getString(R.string.error_download), Toast.LENGTH_SHORT).show()
            }
        }
    }
}


private suspend fun saveImg(c: Context, url: String) {
    withContext(Dispatchers.IO) {
        val result = saveImgInternal(c, url, false)
        withContext(Dispatchers.Main) {
            when(result) {
                1 -> Toast.makeText(c, c.getString(R.string.toast_saved_one), Toast.LENGTH_SHORT).show()
                2 -> Toast.makeText(c, c.getString(R.string.toast_already_exists), Toast.LENGTH_SHORT).show()
                0 -> Toast.makeText(c, c.getString(R.string.error_invalid_image), Toast.LENGTH_SHORT).show() // Добавляем для битых картинок
                else -> Toast.makeText(c, c.getString(R.string.error_download), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun saveImgInternal(c: Context, url: String, isBatch: Boolean): Int {
    Log.d("PicsTok", "📥 Attempting to save image from: $url")
    return try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.connect()

        val contentType = connection.contentType ?: ""
        val contentLength = connection.contentLength

        if (!contentType.startsWith("image/")) {
            connection.disconnect()
            Log.d("PicsTok", "Invalid content type: $contentType")
            return 0
        }

        Log.d("PicsTok", "📊 Content-Type: $contentType, Size: $contentLength bytes")

        var bytes = connection.inputStream.use { it.readBytes() }
        connection.disconnect()
        Log.d("PicsTok", "📦 Downloaded: ${bytes.size} bytes")

        // Конвертация HEIC/HEIF → JPEG
        if (contentType.startsWith("image/heic") || contentType.startsWith("image/heif")) {
            try {
                val heifCoder = HeifCoder()  // ← создаём экземпляр
                val bitmap = heifCoder.decode(bytes)  // ← декодируем в Bitmap

                if (bitmap == null) {
                    Log.e("PicsTok", "HeifCoder: Failed to decode HEIC to Bitmap")
                    return 0
                }

                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, outputStream)
                bytes = outputStream.toByteArray()

                Log.d("PicsTok", "✅ Converted HEIC → JPEG: ${bytes.size} bytes")
            } catch (e: Exception) {
                Log.e("PicsTok", "HEIC conversion failed: ${e.message}", e)
                return 0  // Пропускаем, если не получилось
            }
        }

        if (!isValidImage(bytes, url)) {
            Log.d("PicsTok", "❌ Invalid image - failed signature check")
            return 0
        }

        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        // Проверка на существование (твой код без изменений)
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Pictures/PicsTok%")

        val cursor = c.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )

        var alreadyExists = false
        cursor?.use { cur ->
            while (cur.moveToNext()) {
                val displayName = cur.getString(cur.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                if (displayName.contains(hash.take(12)) || displayName.contains(hash.take(8))) {
                    alreadyExists = true
                    break
                }
            }
        }
        cursor?.close()

        if (alreadyExists) return 2

        // Mime и расширение (после конвертации — всегда jpeg для HEIC)
        val (mimeType, extension) = if (
            contentType.startsWith("image/heic") || contentType.startsWith("image/heif") ||
            (url.contains("~tplv-") && url.endsWith(".image"))
        ) {
            "image/jpeg" to ".jpg"
        } else {
            determineImageFormat(bytes, contentType)
        }

        val fileName = if (isBatch)
            "PT_${hash.take(8)}_${UUID.randomUUID().toString().take(4)}$extension"
        else
            "PT_${hash.take(12)}$extension"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicsTok")
            put(MediaStore.Images.Media.SIZE, bytes.size)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }

        val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Log.d("PicsTok", "Image saved: $fileName (${bytes.size} bytes)")
            1
        } else {
            Log.d("PicsTok", "Failed to insert into MediaStore")
            0
        }
    } catch (e: Exception) {
        Log.e("PicsTok", "💥 Error saving image from $url: ${e.message}", e)
        0
    }
}
private fun determineMimeType(bytes: ByteArray, url: String): String {
    // Проверка по магическим числам
    return when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        ) -> "image/png"
        bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() -> "image/webp"
        url.contains("heic", ignoreCase = true) -> "image/heic"
        else -> "image/jpeg" // fallback
    }
}

private suspend fun preCacheAllImages(
    context: Context,
    urls: List<String>,
    state: ParserState,
    onProgress: (Float) -> Unit
): Int {
    var cachedCount = 0
    var errorCount = 0

    Log.d("PreCache", "🚀 Начинаем предварительное кэширование ${urls.size} изображений")

    urls.chunked(3).forEachIndexed { chunkIndex, chunk ->
        if (!state.isDownloading) {
            Log.i("PreCache", "Кэширование прервано пользователем")
            return@forEachIndexed
        }

        // Обернем в coroutineScope для использования async
        coroutineScope {
            val deferredResults = chunk.map { url ->
                async(Dispatchers.IO) {
                    try {
                        // Проверяем, есть ли уже в кэше
                        if (url in state.cachedImages) {
                            Log.d("PreCache", "✅ Уже в кэше: $url")
                            return@async true
                        }

                        // Загружаем через ImageLoader (так же как в AsyncImage)
                        val imageLoader = (context as MainActivity).imageLoader

                        val result = imageLoader.execute(
                            ImageRequest.Builder(context)
                                .data(url)
                                .allowHardware(false)
                                .httpHeaders(
                                    NetworkHeaders.Builder()
                                        .set("Referer", "https://www.tiktok.com/")
                                        .set(
                                            "User-Agent",
                                            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36"
                                        )
                                        .build()
                                )
                                .build()
                        )

                        val bitmap = result.image?.toBitmap()
                        if (bitmap != null) {
                            val outputStream = ByteArrayOutputStream()
                            val format = when {
                                url.contains(".png", ignoreCase = true) -> Bitmap.CompressFormat.PNG
                                url.contains(".webp", ignoreCase = true) -> Bitmap.CompressFormat.WEBP
                                else -> Bitmap.CompressFormat.JPEG
                            }
                            bitmap.compress(format, 90, outputStream)
                            val byteArray = outputStream.toByteArray()

                            if (byteArray.isNotEmpty() && isValidImage(byteArray, url)) {
                                // Сохраняем в кэш
                                val MAX_CACHE_SIZE = 1000
                                if (state.cachedImages.size >= MAX_CACHE_SIZE) {
                                    val oldest = state.cachedImages.entries
                                        .minByOrNull { it.value.timestamp }
                                    if (oldest != null) {
                                        state.cachedImages.remove(oldest.key)
                                    }
                                }

                                state.cachedImages[url] = CachedImage(
                                    url = url,
                                    data = byteArray,
                                    mimeType = determineMimeType(byteArray, url)
                                )
                                Log.d("PreCache", "✅ Закешировано: $url (${byteArray.size} байт)")
                                true
                            } else {
                                Log.w("PreCache", "❌ Невалидное изображение: $url")
                                false
                            }
                        } else {
                            Log.w("PreCache", "❌ Не удалось загрузить: $url")
                            false
                        }
                    } catch (e: Exception) {
                        Log.e("PreCache", "Ошибка кэширования $url: ${e.message}")
                        false
                    }
                }
            }

            // Ждем завершения всех задач в чанке
            val results = deferredResults.awaitAll()
            cachedCount += results.count { it }
            errorCount += results.count { !it }
        }

        // Обновляем прогресс
        val progress = (chunkIndex + 1).toFloat() / (urls.size / 3f + 1)
        withContext(Dispatchers.Main) {
            onProgress(progress)
        }

        delay(100L) // Небольшая пауза между чанками
    }

    Log.d("PreCache", "🎯 Кэширование завершено. Успешно: $cachedCount, Ошибок: $errorCount")
    return cachedCount
}

private suspend fun downloadAllAsZip(
    context: Context,
    urls: List<String>,
    state: ParserState,
    onProgress: (Float) -> Unit
) {
    state.isDownloading = true
    state.isZipOperation = true
    state.downloadProgress = 0f

    withContext(Dispatchers.IO) {
        try {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "PicsTok"
            ).also { if (!it.exists()) it.mkdirs() }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val zipFile = File(directory, "PicsTok_$timestamp.zip")

            var savedCount = 0
            var missingCount = 0
            var invalidCount = 0

            // 1. ПРЕДВАРИТЕЛЬНОЕ КЭШИРОВАНИЕ (если нужно)
            val urlsToProcess = urls.distinct()
            val uncachedUrls = urlsToProcess.filter { it !in state.cachedImages }

            if (uncachedUrls.isNotEmpty()) {
                Log.d("ZipCreate", "⏳ Начинаем предварительное кэширование ${uncachedUrls.size} изображений...")

                // Первый этап: кэширование
                withContext(Dispatchers.Main) {
                    state.downloadProgress = 0.1f // Начальный прогресс
                }

                val cachedCount = preCacheAllImages(context, uncachedUrls, state) { cacheProgress ->
                    // Используем launch вместо withContext
                    launch(Dispatchers.Main) {
                        val overallProgress = 0.1f + (cacheProgress * 0.4f)
                        onProgress(overallProgress)
                    }
                }

                Log.d("ZipCreate", "✅ Кэшировано $cachedCount из ${uncachedUrls.size} изображений")
            }

            // 2. ФИЛЬТРАЦИЯ КЭШИРОВАННЫХ
            val cachedUrls = urlsToProcess.filter { it in state.cachedImages }

            if (cachedUrls.isEmpty()) {
                Log.w("ZipCreate", "❌ Нет кэшированных изображений для архивации")
                withContext(Dispatchers.Main) {
                    state.isDownloading = false
                    state.isZipOperation = false
                    state.downloadProgress = -1f
                    Toast.makeText(
                        context,
                        context.getString(R.string.zip_no_cached_images),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@withContext
            }

            Log.d("ZipCreate", "📦 Начинаем создание ZIP из ${cachedUrls.size} кэшированных изображений")

            // 3. СОЗДАНИЕ ZIP (50-100%)
            withContext(Dispatchers.Main) {
                state.downloadProgress = 0.5f
            }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zipOut ->
                cachedUrls.forEachIndexed { index, url ->
                    if (!state.isDownloading) {
                        Log.i("ZipCreate", "Создание архива отменено пользователем")
                        return@forEachIndexed
                    }

                    val cachedImage = state.cachedImages[url]
                    if (cachedImage == null) {
                        Log.w("ZipCreate", "Изображение не найдено в кэше: $url")
                        missingCount++
                        return@forEachIndexed
                    }

                    val imageData = cachedImage.data
                    if (imageData.isEmpty()) {
                        Log.w("ZipCreate", "Пустые данные в кэше: $url")
                        missingCount++
                        return@forEachIndexed
                    }

                    if (!isValidImage(imageData, url)) {
                        Log.w("ZipCreate", "Данные из кэша не прошли проверку: $url")
                        invalidCount++
                        return@forEachIndexed
                    }

                    // Определяем расширение
                    val extension = when {
                        cachedImage.mimeType.contains("png") -> ".png"
                        cachedImage.mimeType.contains("webp") -> ".webp"
                        cachedImage.mimeType.contains("heic") -> ".heic"
                        cachedImage.mimeType.contains("gif") -> ".gif"
                        cachedImage.mimeType.contains("bmp") -> ".bmp"
                        else -> ".jpg"
                    }

                    // Генерируем имя файла
                    val shortHash = MessageDigest.getInstance("SHA-256")
                        .digest(imageData)
                        .take(8)
                        .joinToString("") { "%02x".format(it) }

                    val entryName = if (cachedUrls.size == 1) {
                        "image$extension"
                    } else {
                        "img_${savedCount + 1}_$shortHash$extension"
                    }

                    // Добавляем в архив
                    zipOut.putNextEntry(ZipEntry(entryName))
                    zipOut.write(imageData)
                    zipOut.closeEntry()

                    savedCount++

                    // Обновляем прогресс создания ZIP (50-100%)
                    val zipProgress = (index + 1).toFloat() / cachedUrls.size
                    val overallProgress = 0.5f + (zipProgress * 0.5f)

                    withContext(Dispatchers.Main) {
                        onProgress(overallProgress)
                    }

                    delay(10L)
                }
            }

            // Сохраняем состояние загрузки
            val wasDownloading = state.isDownloading

            // ФИНАЛЬНАЯ ОБРАБОТКА
            withContext(Dispatchers.Main) {
                state.isDownloading = false
                state.isZipOperation = false
                state.downloadProgress = -1f

                if (!wasDownloading) {
                    if (zipFile.exists()) zipFile.delete()
                    Toast.makeText(context, R.string.zip_cancelled, Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                if (savedCount == 0) {
                    if (zipFile.exists()) zipFile.delete()
                    val message = when {
                        missingCount == cachedUrls.size -> context.getString(R.string.toast_zip_no_images_added)
                        invalidCount > 0 -> context.getString(R.string.zip_no_valid_images)
                        else -> context.getString(R.string.zip_error)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                } else {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(zipFile.absolutePath),
                        arrayOf("application/zip"),
                        null
                    )

                    val msg = buildString {
                        append(context.getString(R.string.toast_saved_count, savedCount))
                        if (missingCount > 0) {
                            append(" · ")
                            append(context.getString(R.string.toast_skipped, missingCount))
                        }
                        if (invalidCount > 0) {
                            append(" · ")
                            append(context.getString(R.string.zip_invalid_images, invalidCount))
                        }
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

                    Log.d("ZipCreate", "✅ Архив создан: ${zipFile.absolutePath} (${savedCount} файлов)")

                    // Очищаем кэш после успешного создания
                    state.cachedImages.clear()
                }
            }

        } catch (e: Exception) {
            Log.e("ZipCreate", "Критическая ошибка создания ZIP", e)
            withContext(Dispatchers.Main) {
                state.isDownloading = false
                state.isZipOperation = false
                state.downloadProgress = -1f
                Toast.makeText(context, R.string.zip_error, Toast.LENGTH_LONG).show()
            }
        }
    }
}



fun shareImage(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_image_title)))
}

fun shareFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Используем ресурс для заголовка окна выбора приложения
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_archive_title)))
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.share_error), Toast.LENGTH_SHORT).show()
    }



}