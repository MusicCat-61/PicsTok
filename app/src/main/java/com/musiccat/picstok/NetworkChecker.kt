package com.musiccat.picstok

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.io.IOException

sealed class NetworkCheckResult {
    data object SUCCESS : NetworkCheckResult()
    data class FAILURE(@StringRes val messageRes: Int) : NetworkCheckResult()
}



class NetworkChecker(private val context: Context) {

    suspend fun performAllChecks(url: String): NetworkCheckResult {
        return withContext(Dispatchers.IO) {
            // 1. Проверка TikTok сервера
            val tiktokCheck = checkTikTokReachable()
            if (tiktokCheck is NetworkCheckResult.FAILURE) {
                return@withContext tiktokCheck
            }

            // 2. Проверка нашего FastAPI сервера
            val apiCheck = checkApiReachable()
            if (apiCheck is NetworkCheckResult.FAILURE) {
                return@withContext apiCheck
            }

            // 3. Проверка конкретной ссылки
            val urlCheck = checkUrlReachable(url)
            if (urlCheck is NetworkCheckResult.FAILURE) {
                return@withContext urlCheck
            }

            return@withContext NetworkCheckResult.SUCCESS
        }
    }



    private fun checkTikTokReachable(): NetworkCheckResult {
        return try {
            val connection = URL("https://www.tiktok.com").openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()

            val responseCode = connection.responseCode
            connection.disconnect()

            Log.d("NetworkChecker", "TikTok check: $responseCode")

            if (responseCode in 200..399) {
                NetworkCheckResult.SUCCESS
            } else {
                NetworkCheckResult.FAILURE(R.string.error_tiktok_unreachable)
            }
        } catch (e: Exception) {
            Log.e("NetworkChecker", "TikTok unreachable: ${e.message}")
            NetworkCheckResult.FAILURE(R.string.error_tiktok_unreachable)
        }
    }

    private fun checkApiReachable(): NetworkCheckResult {
        return try {
            val apiUrl = "http://206.245.159.225:8000/"
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")

            connection.connect()
            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage
            connection.disconnect()

            Log.d("NetworkChecker", "API check: $responseCode - $responseMessage")

            when {
                responseCode == 200 -> NetworkCheckResult.SUCCESS
                responseCode == 404 -> NetworkCheckResult.SUCCESS
                responseCode == 500 -> NetworkCheckResult.FAILURE(R.string.error_api_server_error)
                responseCode == 503 -> NetworkCheckResult.FAILURE(R.string.error_api_unavailable)
                else -> NetworkCheckResult.FAILURE(R.string.error_api_unreachable)
            }
        } catch (e: SocketTimeoutException) {
            Log.e("NetworkChecker", "API timeout: ${e.message}")
            NetworkCheckResult.FAILURE(R.string.error_api_timeout)
        } catch (e: IOException) {
            Log.e("NetworkChecker", "API network error: ${e.message}")
            NetworkCheckResult.FAILURE(R.string.error_api_unreachable)
        } catch (e: Exception) {
            Log.e("NetworkChecker", "API unknown error: ${e.message}")
            NetworkCheckResult.FAILURE(R.string.error_api_unreachable)
        }
    }

    private fun checkUrlReachable(url: String): NetworkCheckResult {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Accept", "text/html")

            connection.connect()
            val responseCode = connection.responseCode
            connection.disconnect()

            Log.d("NetworkChecker", "URL check [$url]: $responseCode")

            when {
                responseCode == 404 -> NetworkCheckResult.FAILURE(R.string.error_url_not_found)
                responseCode == 410 -> NetworkCheckResult.FAILURE(R.string.error_url_gone)
                responseCode == 403 -> NetworkCheckResult.FAILURE(R.string.error_url_forbidden)
                responseCode == 429 -> NetworkCheckResult.FAILURE(R.string.error_url_rate_limit)
                responseCode in 200..399 -> NetworkCheckResult.SUCCESS
                else -> NetworkCheckResult.FAILURE(R.string.error_url_unreachable)
            }
        } catch (e: Exception) {
            Log.e("NetworkChecker", "URL check failed: ${e.message}")
            NetworkCheckResult.FAILURE(R.string.error_url_unreachable)
        }
    }
}

