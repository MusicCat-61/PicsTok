package com.musiccat.picstok

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

/**
 * Enum for sorting order in gallery
 */
enum class GallerySortOrder {
    NEWEST_FIRST, // Новые сверху
    OLDEST_FIRST  // Старые сверху
}

/**
 * Data class for grouped gallery items by date
 */
data class GalleryGroup(
    val dateLabel: String, // Format: "Сегодня", "Вчера", "25 декабря 2024"
    val dateMillis: Long,  // Для сортировки
    val items: List<GalleryItem>
)

/**
 * Function to load gallery items grouped by date with sorting option
 */
suspend fun loadGalleryGrouped(context: Context, sortOrder: GallerySortOrder = GallerySortOrder.NEWEST_FIRST): List<GalleryGroup> {
    return withContext(Dispatchers.IO) {
        val items = mutableListOf<GalleryItem>()

        // Определяем порядок сортировки для запроса
        val sortOrderSql = when (sortOrder) {
            GallerySortOrder.NEWEST_FIRST -> "${MediaStore.Images.Media.DATE_ADDED} DESC"
            GallerySortOrder.OLDEST_FIRST -> "${MediaStore.Images.Media.DATE_ADDED} ASC"
        }

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED
            ),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%Pictures/PicsTok%"),
            sortOrderSql
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idCol).toString()
                )

                // Try to get the most relevant date (taken > added > modified)
                val dateMillis = when {
                    cursor.getLong(dateTakenCol) > 0 -> cursor.getLong(dateTakenCol)
                    cursor.getLong(dateAddedCol) > 0 -> cursor.getLong(dateAddedCol) * 1000
                    cursor.getLong(dateModifiedCol) > 0 -> cursor.getLong(dateModifiedCol) * 1000
                    else -> System.currentTimeMillis()
                }

                items.add(GalleryItem(uri, cursor.getLong(sizeCol), dateMillis))
            }
        }

        return@withContext groupItemsByDate(context, items, sortOrder)
    }
}

/**
 * Group gallery items by date for display with sorting option
 */
private fun groupItemsByDate(context: Context, items: List<GalleryItem>, sortOrder: GallerySortOrder): List<GalleryGroup> {
    if (items.isEmpty()) return emptyList()

    val groups = mutableListOf<GalleryGroup>()
    val calendar = Calendar.getInstance()
    val todayCalendar = Calendar.getInstance()
    val yesterdayCalendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

    // Reset time components for date comparison
    fun resetTime(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    resetTime(todayCalendar)
    resetTime(yesterdayCalendar)

    // Group items by day
    val itemsByDay = items.groupBy { item ->
        calendar.timeInMillis = item.date
        resetTime(calendar)
        calendar.timeInMillis
    }

    // Sort days based on sort order
    val sortedDays = when (sortOrder) {
        GallerySortOrder.NEWEST_FIRST -> itemsByDay.keys.sortedDescending()
        GallerySortOrder.OLDEST_FIRST -> itemsByDay.keys.sorted()
    }

    val dateFormatFull = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
    val dateFormatCurrentYear = SimpleDateFormat("d MMMM", Locale.getDefault())

    sortedDays.forEach { dayMillis ->
        var itemsForDay = itemsByDay[dayMillis] ?: return@forEach

        // Sort items within each day based on sort order
        itemsForDay = when (sortOrder) {
            GallerySortOrder.NEWEST_FIRST -> itemsForDay.sortedByDescending { it.date }
            GallerySortOrder.OLDEST_FIRST -> itemsForDay.sortedBy { it.date }
        }

        val dateLabel = when {
            dayMillis == todayCalendar.timeInMillis -> context.getString(R.string.sort_order_today)
            dayMillis == yesterdayCalendar.timeInMillis -> context.getString(R.string.sort_order_yesterday)
            else -> {
                calendar.timeInMillis = dayMillis
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                val itemYear = calendar.get(Calendar.YEAR)

                if (itemYear == currentYear) {
                    dateFormatCurrentYear.format(Date(dayMillis))
                } else {
                    dateFormatFull.format(Date(dayMillis))
                }
            }
        }

        groups.add(GalleryGroup(dateLabel, dayMillis, itemsForDay))
    }

    return groups
}
