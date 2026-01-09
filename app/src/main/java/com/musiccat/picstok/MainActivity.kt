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
    var selectedSize by remember { mutableLongStateOf(0L) } // Размер выбранных
    var selectedCount by remember { mutableIntStateOf(0) }
    var onSelectAllTrigger by remember { mutableStateOf(false) } // Триггер для "Выбрать все"
    var onDeleteTrigger by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if ((state.currentTab == 1 || state.currentTab == 2) && isSelectionMode) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.gallery_selected_count, selectedCount, formatFileSize(selectedSize)), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.ExtraBold)
                    }
                },
                navigationIcon = {
                    if ((state.currentTab == 1 || state.currentTab == 2) && isSelectionMode) {
                        TextButton(onClick = { onSelectAllTrigger = true }) {
                            Text(stringResource(R.string.btn_select_all), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                actions = {
                    if ((state.currentTab == 1 || state.currentTab == 2) && isSelectionMode) {
                        TextButton(onClick = { isSelectionMode = false }) {
                            Text(stringResource(R.string.btn_cancel), fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(painterResource(R.drawable.ic_settings), null) }
                }
            )
        },
        floatingActionButton = {
            if (state.currentTab == 1 || state.currentTab == 2) {
                FloatingActionButton(
                    onClick = {
                        if (!isSelectionMode) isSelectionMode = true
                        else if (selectedCount > 0) onDeleteTrigger = true
                    },
                    containerColor = if (isSelectionMode && selectedCount == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(painterResource(R.drawable.ic_delete), null)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = state.currentTab == 0, onClick = { state.currentTab = 0; isSelectionMode = false }, icon = { Icon(painterResource(R.drawable.ic_nav_parser), null) }, label = { Text(stringResource(R.string.tab_parser)) })
                NavigationBarItem(selected = state.currentTab == 1, onClick = { state.currentTab = 1; isSelectionMode = false }, icon = { Icon(painterResource(R.drawable.ic_nav_gallery), null) }, label = { Text(stringResource(R.string.tab_gallery)) })
                NavigationBarItem(selected = state.currentTab == 2, onClick = { state.currentTab = 2; isSelectionMode = false }, icon = { Icon(painterResource(R.drawable.ic_archive), null) }, label = { Text(stringResource(R.string.tab_archives)) })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (state.currentTab) {
                0 -> ParserScreen(state, api, loader)
                1 -> GalleryScreen(
                    isSelectionMode = isSelectionMode,
                    onSelectionChange = { count, size -> selectedCount = count; selectedSize = size },
                    onDeleteRequest = onDeleteTrigger,
                    onSelectAllRequest = onSelectAllTrigger,
                    onOperationFinished = {
                        onDeleteTrigger = false
                        onSelectAllTrigger = false
                        if(it) isSelectionMode = false // if deleted -> exit selection
                    }
                )
                2 -> ArchivesScreen(
                    isSelectionMode = isSelectionMode,
                    onSelectionChange = { count, size -> selectedCount = count; selectedSize = size },
                    onDeleteRequest = onDeleteTrigger,
                    onSelectAllRequest = onSelectAllTrigger,
                    onOperationFinished = {
                        onDeleteTrigger = false
                        onSelectAllTrigger = false
                        if(it) isSelectionMode = false
                    }
                )
            }
        }
    }
    if (showSettings) SettingsSheet(state, loader) { showSettings = false }
}

@Composable
fun ParserScreen(state: ParserState, api: TikTokApi, loader: ImageLoader) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

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

    LaunchedEffect(Unit) { tryPasteFromClipboard() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tryPasteFromClipboard() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            if (state.resultUrls.isEmpty() && !state.isWorking) {
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
                        onClick = {
                            if (state.url.isBlank()) {
                                Toast.makeText(context, context.getString(R.string.error_empty_link), Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            state.isWorking = true
                            state.isFinished = false
                            state.resultUrls = emptyList()
                            state.processedCount = 0
                            state.foundCount = 0
                            scope.launch {
                                try {
                                    val tid = api.startParse(mapOf("url" to state.url.trim()))["task_id"] ?: ""
                                    while (state.isWorking) {
                                        val res = api.getStatus(tid)
                                        val status = res["status"] as? String
                                        if (status == "completed") {
                                            val urls = (res["urls"] as? List<*>)?.map { it.toString() } ?: emptyList()
                                            state.resultUrls = urls.distinct()
                                            state.foundCount = state.resultUrls.size
                                            state.isWorking = false
                                            state.isFinished = true
                                            state.url = ""
                                        } else {
                                            state.processedCount = (res["processed_comments"] as? Number)?.toInt() ?: 0
                                            val urls = (res["urls"] as? List<*>)?.map { it.toString() } ?: emptyList()
                                            state.resultUrls = urls.distinct()
                                            state.foundCount = state.resultUrls.size
                                        }
                                        delay(1500)
                                    }
                                } catch (e: Exception) {
                                    state.isWorking = false
                                    Toast.makeText(context, context.getString(R.string.error_download), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = state.url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text(stringResource(R.string.btn_start)) }
                }
            } else {
                Text(stringResource(R.string.parse_progress, state.processedCount, state.foundCount), fontWeight = FontWeight.Bold)
                if (state.isWorking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))

                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                    items(state.resultUrls) { url ->
                        Box(Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp))) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                                contentDescription = null,
                                imageLoader = loader,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { scope.launch { saveImg(context, url) } },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(32.dp).background(Color.Black.copy(0.5f), CircleShape)
                            ) { Icon(painterResource(R.drawable.ic_download), null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
                if (state.isFinished) Spacer(Modifier.height(90.dp))
            }
        }

        if (state.isFinished) {
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 8.dp, shadowElevation = 16.dp) {
                Row(Modifier.padding(12.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { state.showDownloadAllDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp))
                            Text(stringResource(R.string.btn_download_all), fontSize = 11.sp)
                        }
                        Button(onClick = { state.showZipDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(painterResource(R.drawable.ic_archive), null, Modifier.size(18.dp))
                            Text(stringResource(R.string.btn_zip), fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedIconButton(
                        onClick = { state.resultUrls = emptyList(); state.isFinished = false; state.url = ""; state.processedCount = 0; state.foundCount = 0 },
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.error)
                    ) { Icon(painterResource(R.drawable.ic_clear), null, Modifier.size(32.dp)) }
                }
            }
        }
    }

    if (state.showDownloadAllDialog || state.showZipDialog) {
        val isZip = state.showZipDialog
        AlertDialog(
            onDismissRequest = { if(isZip) state.showZipDialog = false else state.showDownloadAllDialog = false },
            title = { Text(stringResource(if(isZip) R.string.dialog_zip_title else R.string.dialog_download_title)) },
            text = { Text(stringResource(if(isZip) R.string.dialog_zip_text else R.string.dialog_download_text, state.resultUrls.size)) },
            confirmButton = {
                Button(onClick = {
                    state.isZipOperation = isZip
                    if(isZip) state.showZipDialog = false else state.showDownloadAllDialog = false
                    state.currentDownloadJob?.cancel()
                    state.currentDownloadJob = scope.launch {
                        if (isZip) downloadAllAsZip(context, state.resultUrls, state) { state.downloadProgress = it }
                        else saveAll(context, state.resultUrls, state) { state.downloadProgress = it }
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { if(isZip) state.showZipDialog = false else state.showDownloadAllDialog = false }) { Text(stringResource(R.string.btn_cancel)) } }
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
    onDeleteRequest: Boolean,
    onSelectAllRequest: Boolean,
    onOperationFinished: (Boolean) -> Unit // true if items were deleted
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
    }

    LaunchedEffect(Unit) { load() }

    // Подсчет размера выбранных
    LaunchedEffect(selectedItems) {
        val totalSize = selectedItems.sumOf { it.size }
        onSelectionChange(selectedItems.size, totalSize)
    }

    LaunchedEffect(onDeleteRequest) { if (onDeleteRequest) showDeleteDialog = true }

    // Обработка "Выбрать все"
    LaunchedEffect(onSelectAllRequest) {
        if (onSelectAllRequest) {
            selectedItems = items.toSet()
            onOperationFinished(false) // Сбрасываем триггер кнопки
        }
    }

    LaunchedEffect(isSelectionMode) { if (!isSelectionMode) selectedItems = emptySet() }

    Column {
        // Статистика всего хранилища
        if (items.isNotEmpty() && !isSelectionMode) {
            val totalSize = items.sumOf { it.size }
            Text(
                text = stringResource(R.string.gallery_total_stats, items.size, formatFileSize(totalSize)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).align(Alignment.CenterHorizontally)
            )
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.gallery_empty), color = Color.Gray) }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f).padding(2.dp)) {
                items(items) { item ->
                    val isSelected = selectedItems.contains(item)
                    Box(Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                        .clickable { if (isSelectionMode) selectedItems = if (isSelected) selectedItems - item else selectedItems + item }
                    ) {
                        AsyncImage(model = item.uri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (!isSelectionMode) {
                            IconButton(
                                onClick = { shareImage(context, item.uri) },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(28.dp).background(Color.Black.copy(0.4f), CircleShape)
                            ) { Icon(painterResource(R.drawable.ic_share), null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                        }
                        if (isSelected) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(0.5f)), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_check), null, tint = Color.White, modifier = Modifier.size(36.dp))
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
            onDismissRequest = { showDeleteDialog = false; onOperationFinished(false) },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_text, selectedItems.size, formatFileSize(totalSizeToDelete))) },
            confirmButton = {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        selectedItems.forEach { context.contentResolver.delete(it.uri, null, null) }
                        withContext(Dispatchers.Main) {
                            load()
                            showDeleteDialog = false
                            onOperationFinished(true) // Deleted successfully
                            Toast.makeText(context, context.getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                        }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.btn_confirm_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false; onOperationFinished(false) }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }
}

@Composable
fun ArchivesScreen(
    isSelectionMode: Boolean,
    onSelectionChange: (Int, Long) -> Unit,
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
            items = dir.listFiles { _, name -> name.endsWith(".zip") }?.map { ArchiveItem(it) }?.sortedByDescending { it.file.lastModified() } ?: emptyList()
        } else {
            items = emptyList()
        }
    }

    LaunchedEffect(Unit) { load() }

    // Периодическое обновление списка (на случай если архив создался только что)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) load() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(selectedItems) {
        val totalSize = selectedItems.sumOf { it.file.length() }
        onSelectionChange(selectedItems.size, totalSize)
    }
    LaunchedEffect(onDeleteRequest) { if (onDeleteRequest) showDeleteDialog = true }
    LaunchedEffect(onSelectAllRequest) {
        if (onSelectAllRequest) {
            selectedItems = items.toSet()
            onOperationFinished(false)
        }
    }
    LaunchedEffect(isSelectionMode) { if (!isSelectionMode) selectedItems = emptySet() }

    Column {
        if (items.isNotEmpty() && !isSelectionMode) {
            val totalSize = items.sumOf { it.file.length() }
            Text(
                text = stringResource(R.string.gallery_total_stats, items.size, formatFileSize(totalSize)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).align(Alignment.CenterHorizontally)
            )
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.archives_empty), color = Color.Gray) }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(1), modifier = Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items) { item ->
                    val isSelected = selectedItems.contains(item)
                    Card(
                        modifier = Modifier.fillMaxWidth().height(80.dp)
                            .clickable {
                                if (isSelectionMode) selectedItems = if (isSelected) selectedItems - item else selectedItems + item
                                else shareFile(context, item.file) // По клику делимся/открываем
                            },
                        colors = CardDefaults.cardColors(containerColor = if(isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_archive), null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
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
            onDismissRequest = { showDeleteDialog = false; onOperationFinished(false) },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_text, selectedItems.size, formatFileSize(totalSizeToDelete))) },
            confirmButton = {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        selectedItems.forEach { it.file.delete() }
                        withContext(Dispatchers.Main) {
                            load()
                            showDeleteDialog = false
                            onOperationFinished(true)
                            Toast.makeText(context, context.getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                        }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.btn_confirm_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false; onOperationFinished(false) }) { Text(stringResource(R.string.btn_cancel)) } }
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
            validUrls.forEachIndexed { i, u ->
                if (state.isDownloading) {
                    saveImgInternal(c, u, true)
                    withContext(Dispatchers.Main) { onProgress((i + 1).toFloat() / validUrls.size) }
                }
            }
            withContext(Dispatchers.Main) {
                onProgress(-1f)
                val wasActive = state.isDownloading
                state.isDownloading = false
                if (wasActive) Toast.makeText(c, c.getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
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
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val fileName = if (isBatch) "PT_${hash.take(8)}_${UUID.randomUUID().toString().take(4)}.jpg" else "PT_${hash.take(12)}.jpg"

        if (!isBatch) {
            val cursor = c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(fileName), null)
            if (cursor != null && cursor.count > 0) { cursor.close(); return 2 }
            cursor?.close()
        }

        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicsTok")
        }
        c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)?.let { uri ->
            c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            1
        } ?: 0
    } catch (e: Exception) { 0 }
}

private suspend fun downloadAllAsZip(c: Context, urls: List<String>, state: ParserState, onProgress: (Float) -> Unit) {
    state.isDownloading = true
    withContext(Dispatchers.IO) {
        try {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PicsTok")
            if (!directory.exists()) directory.mkdirs()
            val outputFile = File(directory, "PT_Archive_${System.currentTimeMillis()}.zip")
            val validUrls = urls.distinct()
            FileOutputStream(outputFile).use { fos ->
                ZipOutputStream(BufferedOutputStream(fos)).use { zos ->
                    validUrls.forEachIndexed { i, u ->
                        if (state.isDownloading) {
                            try {
                                val bytes = URL(u).openStream().use { it.readBytes() }
                                if (bytes.isNotEmpty()) {
                                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(8)
                                    zos.putNextEntry(ZipEntry("img_${i}_$hash.jpg"))
                                    zos.write(bytes)
                                    zos.closeEntry()
                                }
                            } catch (e: Exception) {}
                            withContext(Dispatchers.Main) { onProgress((i + 1).toFloat() / validUrls.size) }
                        }
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
                Toast.makeText(c, c.getString(R.string.zip_error), Toast.LENGTH_SHORT).show()
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