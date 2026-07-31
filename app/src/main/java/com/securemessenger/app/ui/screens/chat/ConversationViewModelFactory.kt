package com.securemessenger.app.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.securemessenger.app.ui.viewmodel.ConversationViewModel

class ConversationViewModelFactory(
    private val contactId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ConversationViewModel::class.java)) {
            return ConversationViewModel(contactId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
