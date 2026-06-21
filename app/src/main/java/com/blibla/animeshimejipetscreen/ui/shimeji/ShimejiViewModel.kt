package com.blibla.animeshimejipetscreen.ui.shimeji

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blibla.animeshimejipetscreen.data.repo.ShimejiRepository
import com.blibla.animeshimejipetscreen.data.repo.UserRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShimejiViewModel(
    private val shimejiRepo: ShimejiRepository,
    private val userRepo: UserRepository
) : ViewModel() {

    val items = shimejiRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lastShimejiId = userRepo.lastShimejiId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun sync() {
        viewModelScope.launch { runCatching { shimejiRepo.syncActive() } }
    }

    fun setLastUsed(id: Long) {
        viewModelScope.launch { userRepo.setLastShimejiId(id) }
    }
}

