package com.musiccat.picstok

import androidx.compose.runtime.*
import kotlinx.coroutines.*

sealed class PaginationState {
    object IDLE : PaginationState()
    object LOADING : PaginationState()
    object END : PaginationState()
    data class ERROR(val message: String) : PaginationState()
}

class PaginationHelper<T>(
    private val pageSize: Int = 30,
    private val prefetchDistance: Int = 10,
    private val maxCachedPages: Int = 3
) {
    private val _items = mutableStateListOf<T>()
    val items: List<T> = _items

    private val _state = mutableStateOf<PaginationState>(PaginationState.IDLE)
    val state: State<PaginationState> = _state

    private var currentPage = 0
    private var hasMore = true
    private var isLoading = false
    private var loadJob: Job? = null

    private var totalItems: Int = 0

    private var isBlocked = false

    fun setBlocked(blocked: Boolean) {
        isBlocked = blocked
    }

    fun reset() {
        loadJob?.cancel()
        _items.clear()
        currentPage = 0
        hasMore = true
        isLoading = false
        _state.value = PaginationState.IDLE
    }

    fun setTotalItems(count: Int) {
        totalItems = count
    }

    fun loadNextPage(
        scope: CoroutineScope,
        loader: suspend (page: Int, pageSize: Int) -> List<T>
    ) {
        // Проверяем, есть ли еще элементы для загрузки
        if (isBlocked || isLoading || currentPage * pageSize >= totalItems) {
            if (currentPage * pageSize >= totalItems && !isBlocked) {
                _state.value = PaginationState.END
            }
            return
        }

        isLoading = true
        _state.value = PaginationState.LOADING

        loadJob = scope.launch(Dispatchers.IO) {
            try {
                val newItems = loader(currentPage, pageSize)

                withContext(Dispatchers.Main) {
                    if (newItems.isNotEmpty()) {
                        _items.addAll(newItems)
                        currentPage++

                        // Проверяем, достигли ли конца
                        if (currentPage * pageSize >= totalItems) {
                            _state.value = PaginationState.END
                        } else {
                            _state.value = PaginationState.IDLE
                        }
                    } else {
                        // Если не удалось загрузить, считаем что конец
                        _state.value = PaginationState.END
                    }
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = PaginationState.ERROR(e.message ?: "Unknown error")
                    isLoading = false
                }
            }
        }
    }

    fun shouldLoadMore(lastVisibleIndex: Int): Boolean {
        return !isBlocked && !isLoading &&  // ← ДОБАВЬТЕ !isBlocked
                currentPage * pageSize < totalItems &&
                lastVisibleIndex >= _items.size - prefetchDistance
    }

    fun addItemImmediately(item: T) {
        _items.add(item)
    }

    fun addItemsImmediately(newItems: List<T>) {
        _items.addAll(newItems)
    }

    fun clear() {
        reset()
    }
}