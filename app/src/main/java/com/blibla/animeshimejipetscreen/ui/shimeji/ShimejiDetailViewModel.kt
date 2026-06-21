package com.blibla.animeshimejipetscreen.ui.shimeji

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blibla.animeshimejipetscreen.data.repo.ShimejiRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class ShimejiDetailViewModel(
    repo: ShimejiRepository,
    id: Long
) : ViewModel() {
    val item = repo.observeById(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
