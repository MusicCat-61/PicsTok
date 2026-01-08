package com.musiccat.picstok

import android.content.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.musiccat.picstok.ui.theme.PicsTokTheme
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.BufferedOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// --- API ИНТЕРФЕЙС ---
interface TikTokApi {
    @POST("parse")
    suspend fun startParse(@Body body: Map<String, String>): Map<String, String>

    @GET("status/{id}")
    suspend fun getStatus(@Path("id") id: String): Map<String, Any>
}

// --- СОСТОЯНИЕ ПРИЛОЖЕНИЯ ---
class ParserState {
    var currentTab by mutableIntStateOf(0)
    var url by mutableStateOf("")
    var isWorking by mutableStateOf(false)
    var resultUrls by mutableStateOf<List<String>>(emptyList())
    var processedCount by mutableIntStateOf(0)
    var foundCount by mutableIntStateOf(0)
    var downloadProgress by mutableFloatStateOf(-1f)
    var showDownloadAllDialog by mutableStateOf(false)
    var showZipDialog by mutableStateOf(false)

    // Настройки темы
    var themeMode by mutableStateOf("Системная")
    var useMaterialYou by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val api = Retrofit.Builder()
            .baseUrl("http://95.164.17.221:8000/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(TikTokApi::class.java)

        val imageLoader = ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512 * 1024 * 1024)
                    .build()
            }
            .build()

        setContent {
            val state = remember { ParserState() }
            PicsTokTheme(themeMode = state.themeMode, useDynamicColor = state.useMaterialYou) {
                MainScreen(state, api, imageLoader)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: ParserState, api: TikTokApi, loader: ImageLoader) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("PicsTok", fontWeight = FontWeight.ExtraBold) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.currentTab == 0,
                    onClick = { state.currentTab = 0 },
                    icon = { Icon(painterResource(R.drawable.ic_nav_parser), null) },
                    label = { Text(stringResource(R.string.tab_parser)) }
                )
                NavigationBarItem(
                    selected = state.currentTab == 1,
                    onClick = { state.currentTab = 1 },
                    icon = { Icon(painterResource(R.drawable.ic_nav_gallery), null) },
                    label = { Text(stringResource(R.string.tab_gallery)) }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (state.currentTab) {
                0 -> ParserScreen(state, api, loader)
                1 -> GalleryScreen(context)
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
    val clipboard = LocalClipboard.current

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (state.resultUrls.isEmpty() && !state.isWorking) {
            // ЦЕНТРИРОВАННЫЙ БЛОК ВВОДА
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = { state.url = it },
                    label = { Text(stringResource(R.string.input_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (state.url.isNotEmpty()) {
                            IconButton(onClick = { state.url = "" }) {
                                Icon(painterResource(R.drawable.ic_clear), null)
                            }
                        }
                    }
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (state.url.isBlank()) return@Button
                        state.isWorking = true
                        state.resultUrls = emptyList()
                        scope.launch {
                            try {
                                val start = api.startParse(mapOf("url" to state.url))
                                val tid = start["task_id"] ?: return@launch
                                while (true) {
                                    val res = api.getStatus(tid)
                                    state.processedCount = (res["processed_comments"] as? Number)?.toInt() ?: 0
                                    state.foundCount = (res["found_images"] as? Number)?.toInt() ?: 0
                                    if (res["status"] == "completed") {
                                        state.resultUrls = (res["urls"] as? List<*>)?.map { it.toString() } ?: emptyList()
                                        state.isWorking = false
                                        break
                                    }
                                    delay(1500)
                                }
                            } catch (e: Exception) {
                                state.isWorking = false
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.btn_start))
                }
            }
        } else {
            // ЭКРАН РЕЗУЛЬТАТОВ
            Text(
                stringResource(R.string.parse_progress, state.processedCount, state.foundCount),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (state.isWorking) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 16.dp))
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { state.showDownloadAllDialog = true }, Modifier.weight(1f)) {
                    Text(stringResource(R.string.btn_download_all), fontSize = 11.sp)
                }
                Button(onClick = { state.showZipDialog = true }, Modifier.weight(1f)) {
                    Text(stringResource(R.string.btn_zip), fontSize = 11.sp)
                }
                Button(
                    onClick = { state.resultUrls = emptyList(); state.url = "" },
                    Modifier.weight(0.6f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text(stringResource(R.string.btn_clear), fontSize = 11.sp)
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(2.dp)
            ) {
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
                            onClick = {
                                scope.launch {
                                    saveImg(context, url)
                                    Toast.makeText(context, R.string.toast_saved, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .size(28.dp)
                                .background(Color.Black.copy(0.5f), CircleShape)
                        ) {
                            Icon(painterResource(R.drawable.ic_download), null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }

    // ДИАЛОГИ ВНУТРИ ПАРСЕРА
    if (state.showDownloadAllDialog) {
        AlertDialog(
            onDismissRequest = { state.showDownloadAllDialog = false },
            title = { Text(stringResource(R.string.dialog_download_title)) },
            text = { Text(stringResource(R.string.dialog_download_text, state.resultUrls.size)) },
            confirmButton = {
                Button(onClick = {
                    state.showDownloadAllDialog = false
                    scope.launch { saveAll(context, state.resultUrls) { state.downloadProgress = it } }
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { state.showDownloadAllDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    if (state.showZipDialog) {
        AlertDialog(
            onDismissRequest = { state.showZipDialog = false },
            title = { Text(stringResource(R.string.dialog_zip_title)) },
            text = { Text(stringResource(R.string.dialog_zip_text)) },
            confirmButton = {
                Button(onClick = {
                    state.showZipDialog = false
                    scope.launch { downloadAllAsZip(context, state.resultUrls) { state.downloadProgress = it } }
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { state.showZipDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    if (state.downloadProgress >= 0) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Processing...") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { state.downloadProgress }, modifier = Modifier.fillMaxWidth())
                    Text("${(state.downloadProgress * 100).toInt()}%", modifier = Modifier.align(Alignment.End))
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
fun GalleryScreen(context: Context) {
    var images by remember { mutableStateOf(listOf<Uri>()) }

    LaunchedEffect(Unit) {
        val list = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Pictures/PicsTok%")

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                list.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
            }
        }
        images = list
    }

    if (images.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.gallery_empty), color = Color.Gray)
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(2.dp)
        ) {
            items(images) { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(2.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(state: ParserState, loader: ImageLoader, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            ListItem(
                headlineContent = { Text(stringResource(R.string.material_you_label)) },
                supportingContent = { Text(stringResource(R.string.material_you_desc)) },
                trailingContent = {
                    Switch(checked = state.useMaterialYou, onCheckedChange = { state.useMaterialYou = it })
                }
            )

            val options = listOf(
                stringResource(R.string.theme_light),
                stringResource(R.string.theme_dark),
                stringResource(R.string.theme_system)
            )

            Text(stringResource(R.string.theme_system), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { mode ->
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { state.themeMode = mode },
                        label = { Text(mode) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                    Toast.makeText(context, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
            ) {
                Icon(painterResource(R.drawable.ic_clear), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_clear_cache))
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// --- ФУНКЦИИ СОХРАНЕНИЯ ---

private suspend fun saveAll(c: Context, urls: List<String>, onProgress: (Float) -> Unit) {
    withContext(Dispatchers.IO) {
        urls.forEachIndexed { i, url ->
            saveImg(c, url)
            withContext(Dispatchers.Main) { onProgress((i + 1).toFloat() / urls.size) }
        }
        withContext(Dispatchers.Main) {
            onProgress(-1f)
            Toast.makeText(c, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        }
    }
}

private suspend fun saveImg(c: Context, url: String) {
    try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PT_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicsTok")
        }
        c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            c.contentResolver.openOutputStream(uri)?.use { out ->
                URL(url).openStream().use { it.copyTo(out) }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
}

private suspend fun downloadAllAsZip(c: Context, urls: List<String>, onProgress: (Float) -> Unit) {
    withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "PT_Archive_${System.currentTimeMillis()}.zip")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PicsTok/archives")
            }
            val uri = c.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            uri?.let { target ->
                c.contentResolver.openOutputStream(target)?.use { os ->
                    ZipOutputStream(BufferedOutputStream(os)).use { zos ->
                        urls.forEachIndexed { i, url ->
                            try {
                                val bytes = URL(url).openConnection().apply { connectTimeout = 5000 }.getInputStream().use { it.readBytes() }
                                if (bytes.isNotEmpty()) {
                                    zos.putNextEntry(ZipEntry("img_$i.jpg"))
                                    zos.write(bytes)
                                    zos.closeEntry()
                                }
                            } catch (e: Exception) {}
                            withContext(Dispatchers.Main) { onProgress((i + 1).toFloat() / urls.size) }
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        withContext(Dispatchers.Main) {
            onProgress(-1f)
            Toast.makeText(c, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        }
    }
}