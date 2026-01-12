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

data class StorageInfo(val totalBytes: Long, val usedBytes: Long)
