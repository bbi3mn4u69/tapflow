package com.tapflow.card.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapflow.androidauth.AuthFailure
import com.tapflow.card.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CardUiState(
    val busy: Boolean = false,
    val snapshot: CardSnapshot? = null,
    val error: String? = null,
)

class CardViewModel(private val repository: CardRepository) : ViewModel() {
    private val mutable = MutableStateFlow(CardUiState())
    val state = mutable.asStateFlow()

    fun clear() {
        viewModelScope.coroutineContext.cancelChildren()
        mutable.value = CardUiState()
    }

    fun refresh() = run { repository.refresh() }

    fun enroll(label: String) = run { repository.enroll(label) }

    fun disable(id: String) = run { repository.disable(id) }

    fun enable() = run { repository.enable() }

    fun pause() = run {
        repository.pause()
        mutable.value.snapshot?.copy(tapping = false) ?: repository.refresh()
    }

    fun reset() = run { repository.reset() }

    fun more() = run {
        val current = checkNotNull(mutable.value.snapshot)
        val binding = checkNotNull(current.binding)
        val page = repository.more(checkNotNull(binding.cardId), checkNotNull(current.nextCursor))
        current.copy(activity = current.activity + page.first, nextCursor = page.second)
    }

    private fun run(action: suspend () -> CardSnapshot) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                mutable.value = CardUiState(snapshot = action())
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val previous = mutable.value.snapshot
                val localState = withContext(Dispatchers.IO) { repository.localSnapshot(previous) }
                mutable.value =
                    CardUiState(
                        snapshot = localState,
                        error =
                            when (failure) {
                                is AuthFailure -> failure.message
                                is IllegalStateException,
                                is IllegalArgumentException ->
                                    failure.message ?: "Card setup could not be completed."
                                else ->
                                    "Could not refresh your card. Check your connection and try again."
                            },
                    )
            }
        }
    }
}
