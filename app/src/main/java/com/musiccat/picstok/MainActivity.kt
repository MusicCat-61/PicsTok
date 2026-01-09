package com.musiccat.picstok

import android.content.*
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
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

// --- API ---
interface TikTokApi {
    @POST("parse")
    suspend fun startParse(@Body body: Map<String, String>): Map<String, String>
    @GET("status/{id}")
    suspend fun getStatus(@Path("id") id: String): Map<String, Any>
}

// --- Helper Functions ---
fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0) return "0 MB"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(sizeBytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.2f %s", sizeBytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

data class GalleryItem(val uri: Uri, val size: Long, val date: Long)
data class ArchiveItem(val file: File)

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

    // Настройки
    var themeMode by mutableStateOf("Системная")
    var useMaterialYou by mutableStateOf(false)

    var isDownloading by mutableStateOf(false)
    var isZipOperation by mutableStateOf(false)
    var currentDownloadJob: Job? = null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Загрузка настроек
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val initialTheme = prefs.getString("theme_mode", "Системная") ?: "Системная"
        val initialMaterialYou = prefs.getBoolean("material_you", false)

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val api = Retrofit.Builder()
            .baseUrl("http://206.245.159.225:8000/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(TikTokApi::class.java)

        val imageLoader = ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(512 * 1024 * 1024).build() }
            .respectCacheHeaders(false)
            .build()

        setContent {
            val state = remember { ParserState().apply {
                themeMode = initialTheme
                useMaterialYou = initialMaterialYou
            }}

            // Сохранение настроек при изменении
            LaunchedEffect(state.themeMode, state.useMaterialYou) {
                prefs.edit()
                    .putString("theme_mode", state.themeMode)
                    .putBoolean("material_you", state.useMaterialYou)
                    .apply()
            }

            PicsTokTheme(themeMode = state.themeMode, useDynamicColor = state.useMaterialYou) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(state, api, imageLoader)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: ParserState, api: TikTokApi, loader: ImageLoader) {
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
                FloatingActionButton(
                    onClick = {
                        if (!isSelectionMode) {
                            isSelectionMode = true
                        } else if (selectedCount > 0) {
                            onDeleteTrigger = true
                        }
                    },
                    containerColor = if (isSelectionMode && selectedCount == 0) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                ) {
                    Icon(painterResource(R.drawable.ic_delete), null)
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
                0 -> ParserScreen(state, api, loader)
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
        SettingsSheet(state, loader) { showSettings = false }
    }
}
@Composable
fun ParserScreen(state: ParserState, api: TikTokApi, loader: ImageLoader) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var pollingJob by remember { mutableStateOf<Job?>(null) }
    var selectedUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var validImageUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var showNothingSelectedDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Кэш для уже проверенных URL
    var checkedUrlsCache by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Список поддерживаемых расширений
    val supportedExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".jfif")

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
                    state.url = text
                }
            }
        }
    }

    fun startParsing(url: String) {
        if (url.isBlank()) {
            Toast.makeText(context, context.getString(R.string.error_empty_link), Toast.LENGTH_SHORT).show()
            return
        }

        state.isWorking = true
        state.isFinished = false
        state.resultUrls = emptyList()
        state.processedCount = 0
        state.foundCount = 0
        validImageUrls = emptyList()
        selectedUrls = emptySet()
        isSelectionMode = false
        showNothingSelectedDialog = false
        checkedUrlsCache = emptySet()

        pollingJob?.cancel()

        pollingJob = scope.launch {
            try {
                val response = api.startParse(mapOf("url" to url.trim()))
                val taskId = response["task_id"] ?: ""

                // Периодический опрос статуса
                while (state.isWorking && isActive) {
                    val res = withContext(Dispatchers.IO) {
                        api.getStatus(taskId)
                    }

                    val status = res["status"] as? String
                    val processed = (res["processed_comments"] as? Number)?.toInt() ?: 0
                    val urls = (res["urls"] as? List<*>)?.map { it.toString() } ?: emptyList()

                    // Фильтруем пустые/некорректные URL
                    val validUrls = urls.filter {
                        it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://"))
                    }.distinct()

                    // Сохраняем ВСЕ найденные URL (для отображения в реальном времени)
                    state.resultUrls = validUrls
                    state.processedCount = processed

                    // Проверяем только НОВЫЕ URL, которых еще нет в кэше
                    val newUrls = validUrls.filter { it !in checkedUrlsCache }

                    if (newUrls.isNotEmpty()) {
                        // Обновляем кэш СРАЗУ (чтобы не проверять повторно)
                        checkedUrlsCache = checkedUrlsCache + newUrls

                        // Проверяем новые URL асинхронно и параллельно
                        val chunkResults = newUrls.chunked(5).map { chunk ->
                            async(Dispatchers.IO) {
                                chunk.filter { url ->
                                    // 1. Проверяем расширение файла
                                    val lowerUrl = url.lowercase()
                                    val hasSupportedExtension = listOf(
                                        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp",
                                        ".jfif", ".heic", ".heif", ".image"
                                    ).any { ext -> lowerUrl.contains(ext) }

                                    if (!hasSupportedExtension) {
                                        return@filter false
                                    }

                                    // 2. Пытаемся загрузить заголовок (быстрая проверка)
                                    try {
                                        val connection = URL(url).openConnection() as HttpURLConnection
                                        connection.requestMethod = "HEAD"
                                        connection.connectTimeout = 1500
                                        connection.readTimeout = 1500
                                        connection.connect()

                                        val contentType = connection.contentType ?: ""
                                        val contentLength = connection.contentLength

                                        connection.disconnect()

                                        // Проверяем что это изображение и не пустое
                                        contentType.startsWith("image/") && contentLength > 100
                                    } catch (e: Exception) {
                                        // Если HEAD не работает, пробуем GET первых байтов
                                        try {
                                            val connection = URL(url).openConnection() as HttpURLConnection
                                            connection.connectTimeout = 2000
                                            connection.readTimeout = 2000
                                            connection.connect()

                                            val contentType = connection.contentType ?: ""
                                            val input = connection.inputStream
                                            val buffer = ByteArray(128) // Читаем только 128 байт
                                            val bytesRead = input.read(buffer)
                                            input.close()
                                            connection.disconnect()

                                            contentType.startsWith("image/") && bytesRead > 0
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
                                // ОБНОВЛЯЕМ UI ПОСЛЕ КАЖДОГО ЧАНКА
                                withContext(Dispatchers.Main) {
                                    validImageUrls = validImageUrls + validChunk
                                    state.foundCount = validImageUrls.size
                                }
                            }
                        }
                    }

                    if (status == "completed") {
                        // После завершения делаем финальную проверку всех оставшихся картинок
                        val finalUrls = validUrls.filter { it !in checkedUrlsCache }
                        if (finalUrls.isNotEmpty()) {
                            val finalValidUrls = finalUrls.filter { url ->
                                try {
                                    val connection = URL(url).openConnection() as HttpURLConnection
                                    connection.requestMethod = "HEAD"
                                    connection.connectTimeout = 2000
                                    connection.readTimeout = 2000
                                    connection.connect()

                                    val contentType = connection.contentType ?: ""
                                    val contentLength = connection.contentLength

                                    connection.disconnect()

                                    contentType.startsWith("image/") && contentLength > 100
                                } catch (e: Exception) {
                                    false
                                }
                            }

                            withContext(Dispatchers.Main) {
                                validImageUrls = validImageUrls + finalValidUrls
                                state.foundCount = validImageUrls.size
                            }
                        }

                        state.isWorking = false
                        state.isFinished = true
                        state.url = ""
                        break
                    }

                    delay(1000) // Уменьшил задержку до 1 секунды
                }
            } catch (e: Exception) {
                state.isWorking = false
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.error_download), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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

    // Сохраняем состояние при переключении вкладок
    LaunchedEffect(state.currentTab) {
        if (state.currentTab == 0) {
            // При возврате на вкладку парсера восстанавливаем состояние
            if (state.isFinished) {
                // Если парсинг был завершен, показываем результаты
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            if (validImageUrls.isEmpty() && !state.isWorking && !state.isFinished) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    OutlinedTextField(
                        value = state.url,
                        onValueChange = { state.url = it },
                        label = { Text(stringResource(R.string.input_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { startParsing(state.url) },
                        enabled = state.url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text(stringResource(R.string.btn_start)) }
                }
            } else {
                // Показываем статистику
                Column {
                    Text(
                        stringResource(R.string.parse_progress_comments, state.processedCount),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        stringResource(R.string.parse_progress_images, state.foundCount),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

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
                    modifier = Modifier.weight(1f).padding(top = 8.dp)
                ) {
                    // Используем только пригодные URL
                    items(validImageUrls) { url ->
                        val isSelected = selectedUrls.contains(url)
                        Box(
                            Modifier
                                .padding(2.dp)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (isSelectionMode) {
                                        selectedUrls =
                                            if (isSelected) selectedUrls - url else selectedUrls + url
                                    }
                                }
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(url)
                                    .crossfade(true)
                                    .size(200, 200)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .placeholder(R.drawable.ic_image_placeholder)
                                    .error(R.drawable.ic_broken_image)
                                    .allowHardware(false) // Для лучшей совместимости
                                    .build(),
                                contentDescription = null,
                                imageLoader = loader,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onError = { // Обработка ошибок загрузки
                                    // Можно логировать ошибку
                                }
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

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Button(
                                onClick = {
                                    state.showDownloadAllDialog = true
                                },
                                modifier = Modifier.weight(1f).height(48.dp)
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
                                    state.showZipDialog = true
                                },
                                modifier = Modifier.weight(1f).height(48.dp)
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
    var items by remember { mutableStateOf(listOf<GalleryItem>()) }
    var selectedItems by remember { mutableStateOf(setOf<GalleryItem>()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    fun load() {
        val list = mutableListOf<GalleryItem>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_ADDED),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%Pictures/PicsTok%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol))
                list.add(GalleryItem(uri, c.getLong(sizeCol), c.getLong(dateCol)))
            }
        }
        items = list
        // Уведомляем родителя об общем количестве
        onTotalItemsChanged(list.size)
    }

    LaunchedEffect(Unit) { load() }

    // Подсчет размера выбранных и уведомление родителя
    LaunchedEffect(selectedItems) {
        val totalSize = selectedItems.sumOf { it.size }
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
            if (selectedItems.size == items.size) {
                // Если уже все выбраны - снимаем выделение
                selectedItems = emptySet()
            } else {
                // Иначе выбираем все
                selectedItems = items.toSet()
            }
            onOperationFinished(false) // Сбрасываем триггер кнопки
        }
    }

    // Сброс выделения при выходе из режима выбора
    LaunchedEffect(isSelectionMode) {
        if (!isSelectionMode) {
            selectedItems = emptySet()
        }
    }

    Column {
        // Статистика всего хранилища
        if (items.isNotEmpty() && !isSelectionMode) {
            val totalSize = items.sumOf { it.size }
            Text(
                text = stringResource(R.string.gallery_total_stats, items.size, formatFileSize(totalSize)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).align(Alignment.CenterHorizontally)
            )
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.gallery_empty), color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f).padding(2.dp)
            ) {
                items(items) { item ->
                    val isSelected = selectedItems.contains(item)
                    Box(
                        Modifier
                            .padding(2.dp)
                            .aspectRatio(1f)
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
            val totalSize = items.sumOf { it.file.length() }
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
fun SettingsSheet(state: ParserState, loader: ImageLoader, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var cacheSizeStr by remember { mutableStateOf("0 MB") }
    var canClear by remember { mutableStateOf(false) }

    fun updateCacheInfo() {
        val size = loader.diskCache?.size ?: 0L
        canClear = size > 0 || (loader.memoryCache?.size ?: 0) > 0
        cacheSizeStr = formatFileSize(size)
    }

    LaunchedEffect(Unit) { updateCacheInfo() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) // Увеличен шрифт
            Spacer(Modifier.height(16.dp))

            val isAndroid12 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ListItem(
                headlineContent = { Text(stringResource(R.string.material_you_label), fontSize = 18.sp) }, // Увеличен
                supportingContent = { Text(stringResource(R.string.material_you_desc), fontSize = 14.sp) },
                trailingContent = { Switch(checked = state.useMaterialYou, onCheckedChange = { state.useMaterialYou = it }, enabled = isAndroid12) }
            )

            Text(stringResource(R.string.theme_label), modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium) // Увеличен
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

            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.cache_occupied, cacheSizeStr), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            Button(
                onClick = {
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                    updateCacheInfo()
                    Toast.makeText(context, context.getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show()
                },
                enabled = canClear,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(stringResource(R.string.btn_clear_cache), fontSize = 16.sp)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// --- Загрузка и шаринг (стандартные функции) ---

private suspend fun saveAll(c: Context, urls: List<String>, state: ParserState, onProgress: (Float) -> Unit) {
    state.isDownloading = true
    withContext(Dispatchers.IO) {
        try {
            val validUrls = urls.distinct()
            var savedCount = 0
            var skippedCount = 0
            var errorCount = 0

            validUrls.forEachIndexed { i, u ->
                if (state.isDownloading) {
                    val result = saveImgInternal(c, u, true)
                    when (result) {
                        1 -> savedCount++
                        2 -> skippedCount++
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
                            append(c.getString(R.string.toast_saved, savedCount))
                        }
                        if (skippedCount > 0) {
                            if (isNotEmpty()) append(" ")
                            append(c.getString(R.string.toast_skipped, skippedCount))
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
                1 -> Toast.makeText(c, c.getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
                2 -> Toast.makeText(c, c.getString(R.string.toast_already_exists), Toast.LENGTH_SHORT).show()
                else -> Toast.makeText(c, c.getString(R.string.error_download), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun saveImgInternal(c: Context, url: String, isBatch: Boolean): Int {
    return try {
        val bytes = URL(url).openStream().use { it.readBytes() }

        // ФИЛЬТР: проверяем, что картинка не пустая (уменьшил минимальный размер)
        if (bytes.isEmpty() || bytes.size < 100) { // Менее 100 байт - точно пустая
            return 0
        }

        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        // Проверяем, существует ли уже картинка с таким хешом
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
        cursor?.use { c ->
            while (c.moveToNext()) {
                val displayName = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                // Извлекаем хеш из имени файла
                if (displayName.contains(hash.take(12)) || displayName.contains(hash.take(8))) {
                    alreadyExists = true
                    break
                }
            }
        }
        cursor?.close()

        if (alreadyExists) {
            return 2 // Картинка уже существует
        }

        val fileName = if (isBatch) "PT_${hash.take(8)}_${UUID.randomUUID().toString().take(4)}.jpg" else "PT_${hash.take(12)}.jpg"

        // Определяем реальный MIME type
        val mimeType = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes.slice(0..7).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
            bytes.size >= 6 && bytes.slice(0..5).toByteArray().contentEquals(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)) -> "image/gif"
            bytes.size >= 6 && bytes.slice(0..5).toByteArray().contentEquals(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)) -> "image/gif"
            bytes.size >= 12 && bytes.slice(0..11).toByteArray().contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)) -> "image/webp"
            else -> "image/jpeg"
        }

        val extension = when (mimeType) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }

        val finalFileName = fileName.replace(".jpg", extension)

        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicsTok")
            put(MediaStore.Images.Media.SIZE, bytes.size)
        }
        c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)?.let { uri ->
            c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            1
        } ?: 0
    } catch (e: Exception) {
        e.printStackTrace() // Добавьте лог для отладки
        0
    }
}

private suspend fun downloadAllAsZip(c: Context, urls: List<String>, state: ParserState, onProgress: (Float) -> Unit) {
    state.isDownloading = true
    withContext(Dispatchers.IO) {
        try {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PicsTok")
            if (!directory.exists()) directory.mkdirs()
            val outputFile = File(directory, "PT_Archive_${System.currentTimeMillis()}.zip")
            val validUrls = urls.distinct()

            // Получаем список уже существующих хешей из галереи
            val existingHashes = mutableSetOf<String>()
            val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%Pictures/PicsTok%")

            c.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    // Извлекаем хеш из имени файла: PT_XXXXXXXXXXXX.jpg или PT_XXXXXXXX_XXXX.jpg
                    val hashMatch = Regex("PT_([a-f0-9]{8,12})").find(displayName)
                    hashMatch?.groupValues?.get(1)?.let { hash ->
                        existingHashes.add(hash)
                    }
                }
            }

            FileOutputStream(outputFile).use { fos ->
                ZipOutputStream(BufferedOutputStream(fos)).use { zos ->
                    var savedCount = 0
                    var skippedCount = 0
                    validUrls.forEachIndexed { i, u ->
                        if (state.isDownloading) {
                            try {
                                val bytes = URL(u).openStream().use { it.readBytes() }

                                // ФИЛЬТР: проверяем, что картинка не пустая
                                if (bytes.isNotEmpty() && bytes.size >= 1024) {
                                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                                    val shortHash = hash.take(8)

                                    // Проверяем, есть ли уже такая картинка в галерее
                                    if (existingHashes.any { hash.contains(it) || shortHash == it.take(8) }) {
                                        skippedCount++
                                    } else {
                                        zos.putNextEntry(ZipEntry("img_${savedCount}_$shortHash.jpg"))
                                        zos.write(bytes)
                                        zos.closeEntry()
                                        savedCount++
                                    }
                                }
                            } catch (e: Exception) {
                                // Игнорируем ошибки загрузки отдельных картинок
                            }
                            withContext(Dispatchers.Main) { onProgress((i + 1).toFloat() / validUrls.size) }
                        }
                    }

                    // Если не было сохранено ни одной картинки
                    if (savedCount == 0) {
                        val message = if (skippedCount > 0) {
                            "All $skippedCount images already exist in gallery"
                        } else {
                            "No valid images found for archive"
                        }
                        throw IOException(message)
                    }
                }
            }

            if (state.isDownloading) {
                MediaScannerConnection.scanFile(c, arrayOf(outputFile.absolutePath), arrayOf("application/zip")) { _, _ -> }
                withContext(Dispatchers.Main) {
                    onProgress(-1f)
                    state.isDownloading = false
                    Toast.makeText(c, c.getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onProgress(-1f)
                state.isDownloading = false

                // Более информативное сообщение об ошибке
                val errorMessage = when {
                    e.message?.contains("All images already exist") == true -> {
                        c.getString(R.string.all_images_already_exist)
                    }
                    e.message?.contains("No valid images") == true -> {
                        c.getString(R.string.zip_no_valid_images)
                    }
                    else -> {
                        c.getString(R.string.zip_error)
                    }
                }
                Toast.makeText(c, errorMessage, Toast.LENGTH_SHORT).show()
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