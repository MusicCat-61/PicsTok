package com.musiccat.picstok

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File
import androidx.core.net.toUri

/**
 * Подсчёт размера всех изображений в папке Pictures/PicsTok через MediaStore
 */
fun getGallerySizeBytes(context: Context): Long {
    var total = 0L
    val projection = arrayOf(MediaStore.Images.Media.SIZE)
    val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
    val selectionArgs = arrayOf("%Pictures/PicsTok%")

    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        null
    )?.use { cursor ->
        val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        while (cursor.moveToNext()) {
            total += cursor.getLong(sizeIndex)
        }
    }
    return total
}

/**
 * Подсчёт размера всех .zip файлов в Documents/PicsTok
 */
fun getArchivesSizeBytes(): Long {
    var total = 0L
    val directory = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "PicsTok"
    )
    if (directory.exists() && directory.isDirectory) {
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.extension.equals("zip", ignoreCase = true)) {
                total += file.length()
            }
        }
    }
    return total
}

/**
 * Подсчёт суммарного размера выбранных URI (самый точный способ)
 */
fun getSelectedSizeBytes(context: Context, selectedUris: List<String>): Long {
    var total = 0L
    selectedUris.forEach { uriString ->
        val uri = uriString.toUri()
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                total += cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
            }
        }
    }
    return total
}

/**
 * Получение информации о внутреннем хранилище
 */
fun getInternalStorageInfo(): StorageInfo {
    val path = Environment.getDataDirectory()
    val stat = StatFs(path.path)
    val blockSize = stat.blockSizeLong
    val totalBlocks = stat.blockCountLong
    val availableBlocks = stat.availableBlocksLong

    val totalBytes = totalBlocks * blockSize
    val availableBytes = availableBlocks * blockSize
    val usedBytes = totalBytes - availableBytes

    return StorageInfo(totalBytes, usedBytes)
}

/**
 * Получение общего объема оперативной памяти устройства в байтах
 */
fun getTotalDeviceMemoryBytes(context: Context): Long {
    return try {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        memoryInfo.totalMem
    } catch (e: Exception) {
        // Fallback значения для разных устройств
        when {
            android.os.Build.VERSION.SDK_INT >= 26 -> 4L * 1024 * 1024 * 1024 // 4GB
            else -> 2L * 1024 * 1024 * 1024 // 2GB
        }
    }
}

/**
 * Получение доступной оперативной памяти в байтах
 */
fun getAvailableDeviceMemoryBytes(context: Context): Long {
    return try {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        memoryInfo.availMem
    } catch (e: Exception) {
        getTotalDeviceMemoryBytes(context) / 2 // 50% как fallback
    }
}

/**
 * Рассчитывает оптимальный размер дискового кеша в зависимости от свободного места
 * Возвращает размер в байтах
 */
fun calculateOptimalDiskCacheSize(context: Context): Long {
    val storageInfo = getInternalStorageInfo()
    val freeStorageBytes = storageInfo.totalBytes - storageInfo.usedBytes

    // Стратегия: занимаем не более 2% от свободного места, но не менее 50MB и не более 500MB
    val maxCacheSize = (freeStorageBytes * 0.02).toLong()

    return when {
        maxCacheSize < 50L * 1024 * 1024 -> 50L * 1024 * 1024 // Минимум 50MB
        maxCacheSize > 500L * 1024 * 1024 -> 500L * 1024 * 1024 // Максимум 500MB
        else -> maxCacheSize
    }
}

/**
 * Рассчитывает оптимальный размер оперативного кеша в зависимости от ОЗУ
 * Возвращает размер в байтах
 */
fun calculateOptimalMemoryCacheSize(context: Context): Long {
    val totalMemoryBytes = getTotalDeviceMemoryBytes(context)

    // Стратегия: занимаем не более 25% от общей ОЗУ, но не менее 32MB и не более 256MB
    val maxMemoryCache = (totalMemoryBytes * 0.25).toLong()

    return when {
        maxMemoryCache < 32L * 1024 * 1024 -> 32L * 1024 * 1024 // Минимум 32MB
        maxMemoryCache > 256L * 1024 * 1024 -> 256L * 1024 * 1024 // Максимум 256MB
        else -> maxMemoryCache
    }
}

/**
 * Получение размера дискового кеша Coil
 */
fun getCoilDiskCacheSize(context: Context): Long {
    val cacheDir = File(context.cacheDir, "image_cache")
    return if (cacheDir.exists() && cacheDir.isDirectory) {
        cacheDir.walk().filter { it.isFile }.sumOf { it.length() }
    } else {
        0L
    }
}

/**
 * Получение информации о всех видах памяти
 */
data class MemoryInfo(
    val totalDeviceMemory: Long,
    val availableDeviceMemory: Long,
    val optimalDiskCacheSize: Long,
    val optimalMemoryCacheSize: Long,
    val currentDiskCacheSize: Long
)

fun getMemoryInfo(context: Context): MemoryInfo {
    return MemoryInfo(
        totalDeviceMemory = getTotalDeviceMemoryBytes(context),
        availableDeviceMemory = getAvailableDeviceMemoryBytes(context),
        optimalDiskCacheSize = calculateOptimalDiskCacheSize(context),
        optimalMemoryCacheSize = calculateOptimalMemoryCacheSize(context),
        currentDiskCacheSize = getCoilDiskCacheSize(context)
    )
}

data class StorageInfo(val totalBytes: Long, val usedBytes: Long)