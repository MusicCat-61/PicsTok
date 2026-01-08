package com.musiccat.picstok

import android.content.*
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.provider.MediaStore
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.ImageLoader
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
    var themeMode by mutableStateOf("Системная")
    var useMaterialYou by mutableStateOf(false)
    var isDownloading by mutableStateOf(false)
    var isZipOperation by mutableStateOf(false) // Новый флаг для точного определения типа отмены
    var currentDownloadJob: Job? = null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            val state = remember { ParserState() }
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
    var selectedCount by remember { mutableIntStateOf(0) }
    var onDeleteTrigger by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if (state.currentTab == 1 && isSelectionMode) {
                        Text(stringResource(R.string.gallery_selected_count, selectedCount), fontSize = 18.sp)
                    } else {
                        Text("PicsTok", fontWeight = FontWeight.ExtraBold)
                    }
                },
                actions = {
                    if (state.currentTab == 1 && isSelectionMode) {
                        TextButton(onClick = { isSelectionMode = false }) {
                            Text(stringResource(R.string.btn_cancel), fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(painterResource(R.drawable.ic_settings), null) }
                }
            )
        },
        floatingActionButton = {
            if (state.currentTab == 1) {
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
                NavigationBarItem(selected = state.currentTab == 1, onClick = { state.currentTab = 1 }, icon = { Icon(painterResource(R.drawable.ic_nav_gallery), null) }, label = { Text(stringResource(R.string.tab_gallery)) })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (state.currentTab) {
                0 -> ParserScreen(state, api, loader)
                1 -> GalleryScreen(isSelectionMode, { selectedCount = it }, onDeleteTrigger) {
                    onDeleteTrigger = false
                    isSelectionMode = false
                }
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

    // Функция для вставки из буфера
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

    // Авто-вставка при запуске
    LaunchedEffect(Unit) {
        tryPasteFromClipboard()
    }

    // Авто-вставка при возврате в приложение
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                tryPasteFromClipboard()
            }
        }
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),

                    ) { Text(stringResource(R.string.btn_start)) }
                }
            } else {
                Text(stringResource(R.string.parse_progress, state.processedCount, state.foundCount), fontWeight = FontWeight.Bold)
                if (state.isWorking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))

                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                    items(state.resultUrls) { url ->
                        Box(Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp))) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(url)
                                    .crossfade(true)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .networkCachePolicy(CachePolicy.ENABLED)
                                    .build(),
                                contentDescription = null,
                                imageLoader = loader,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { scope.launch { saveImg(context, url) } },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(32.dp).background(Color.Black.copy(0.5f), CircleShape)
                            ) {
                                Icon(painterResource(R.drawable.ic_download), null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
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
                            Text(stringResource(R.string.btn_download_all), fontSize = 11.sp, maxLines = 1)
                        }
                        Button(onClick = { state.showZipDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(painterResource(R.drawable.ic_archive), null, Modifier.size(18.dp))
                            Text(stringResource(R.string.btn_zip), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedIconButton(
                        onClick = {
                            state.resultUrls = emptyList();
                            state.isFinished = false;
                            state.url = "";
                            state.processedCount = 0;
                            state.foundCount = 0;
                        },
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Icon(painterResource(R.drawable.ic_clear), null, Modifier.size(32.dp))
                    }
                }
            }
        }
    }

    if (state.showDownloadAllDialog) {
        AlertDialog(
            onDismissRequest = { state.showDownloadAllDialog = false },
            title = { Text(stringResource(R.string.dialog_download_title)) },
            text = { Text(stringResource(R.string.dialog_download_text, state.resultUrls.size)) },
            confirmButton = {
                Button(onClick = {
                    state.isZipOperation = false // Обычная загрузка
                    state.showDownloadAllDialog = false
                    state.currentDownloadJob?.cancel()
                    state.currentDownloadJob = scope.launch {
                        saveAll(context, state.resultUrls, state) { state.downloadProgress = it }
                    }
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = { TextButton(onClick = { state.showDownloadAllDialog = false }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }

    if (state.showZipDialog) {
        AlertDialog(
            onDismissRequest = { state.showZipDialog = false },
            title = { Text(stringResource(R.string.dialog_zip_title)) },
            text = { Text(stringResource(R.string.dialog_zip_text)) },
            confirmButton = {
                Button(onClick = {
                    state.isZipOperation = true // Операция ZIP
                    state.showZipDialog = false
                    state.currentDownloadJob?.cancel()
                    state.currentDownloadJob = scope.launch {
                        downloadAllAsZip(context, state.resultUrls, state) { state.downloadProgress = it }
                    }
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = { TextButton(onClick = { state.showZipDialog = false }) { Text(stringResource(R.string.btn_cancel)) } }
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
                    // Используем отдельный флаг, который не сбросится диалогом
                    val msg = if (state.isZipOperation) context.getString(R.string.zip_cancelled)
                    else context.getString(R.string.download_cancelled)
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
fun GalleryScreen(isSelectionMode: Boolean, onSelectionChange: (Int) -> Unit, onDeleteRequest: Boolean, onDeleteFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf(listOf<Uri>()) }
    var selectedImages by remember { mutableStateOf(setOf<Uri>()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    fun load() {
        val list = mutableListOf<Uri>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%Pictures/PicsTok%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) list.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)))
        }
        images = list
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(selectedImages) { onSelectionChange(selectedImages.size) }
    LaunchedEffect(onDeleteRequest) { if (onDeleteRequest) showDeleteDialog = true }
    LaunchedEffect(isSelectionMode) { if (!isSelectionMode) selectedImages = emptySet() }

    if (images.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.gallery_empty), color = Color.Gray) }
    } else {
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().padding(2.dp)) {
            items(images) { uri ->
                val isSelected = selectedImages.contains(uri)
                Box(Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                    .clickable { if (isSelectionMode) selectedImages = if (isSelected) selectedImages - uri else selectedImages + uri }
                ) {
                    AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)

                    if (!isSelectionMode) {
                        IconButton(
                            onClick = { shareImage(context, uri) },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(28.dp).background(Color.Black.copy(0.4f), CircleShape)
                        ) {
                            Icon(painterResource(R.drawable.ic_share), null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
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

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false; onDeleteFinished() },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_text, selectedImages.size)) },
            confirmButton = {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        selectedImages.forEach { context.contentResolver.delete(it, null, null) }
                        withContext(Dispatchers.Main) {
                            load()
                            showDeleteDialog = false
                            onDeleteFinished()
                            Toast.makeText(context, context.getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                        }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.btn_confirm_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false; onDeleteFinished() }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(state: ParserState, loader: ImageLoader, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var cacheSize by remember { mutableStateOf("0 MB") }
    var canClear by remember { mutableStateOf(false) }

    fun updateCacheInfo() {
        val size = loader.diskCache?.size ?: 0L
        canClear = size > 0 || (loader.memoryCache?.size ?: 0) > 0
        cacheSize = "${String.format("%.2f", size / 1024.0 / 1024.0)} MB"
    }

    LaunchedEffect(Unit) { updateCacheInfo() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            val isAndroid12 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ListItem(
                headlineContent = { Text(stringResource(R.string.material_you_label)) },
                supportingContent = { Text(stringResource(R.string.material_you_desc)) },
                trailingContent = { Switch(checked = state.useMaterialYou, onCheckedChange = { state.useMaterialYou = it }, enabled = isAndroid12) }
            )

            Text(stringResource(R.string.theme_label), modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(stringResource(R.string.theme_light), stringResource(R.string.theme_dark), stringResource(R.string.theme_system)).forEach { mode ->
                    FilterChip(selected = state.themeMode == mode, onClick = { state.themeMode = mode }, label = { Text(mode) })
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.cache_occupied, cacheSize), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp))
            Button(
                onClick = {
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                    updateCacheInfo()
                    Toast.makeText(context, context.getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show()
                },
                enabled = canClear,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.btn_clear_cache))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

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
                if (wasActive) {
                    Toast.makeText(c, c.getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: CancellationException) {
            withContext(Dispatchers.Main) {
                onProgress(-1f)
                state.isDownloading = false
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

        val fileName = if (isBatch) "PT_${hash.take(8)}_${UUID.randomUUID().toString().take(4)}.jpg"
        else "PT_${hash.take(12)}.jpg"

        if (!isBatch) {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            val cursor = c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, arrayOf(fileName), null)
            if (cursor != null && cursor.count > 0) {
                cursor.close()
                return 2
            }
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
        } catch (e: CancellationException) {
            withContext(Dispatchers.Main) {
                onProgress(-1f)
                state.isDownloading = false
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