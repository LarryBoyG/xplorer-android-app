package com.example.xirolite

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object LiveViewSessionRegistry {
    private val _viewerActive = MutableStateFlow(false)
    val viewerActive: StateFlow<Boolean> = _viewerActive

    fun setViewerActive(active: Boolean) {
        _viewerActive.value = active
    }
}
