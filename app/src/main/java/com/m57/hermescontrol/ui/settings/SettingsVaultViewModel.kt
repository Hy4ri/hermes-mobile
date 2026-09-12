package com.m57.hermescontrol.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.VaultItem
import com.m57.hermescontrol.data.model.VaultSource
import com.m57.hermescontrol.data.ws.VaultRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsVaultUiState(
    val sources: List<VaultSource> = emptyList(),
    val items: List<VaultItem> = emptyList(),
    val isLoading: Boolean = false,
    val isActionInProgress: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val unlockDialogSource: VaultSource? = null,
)

class SettingsVaultViewModel(
    private val vaultRepo: VaultRepository = VaultRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsVaultUiState())
    val uiState: StateFlow<SettingsVaultUiState> = _uiState.asStateFlow()

    init {
        loadVaultData()
    }

    fun loadVaultData() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val sources = vaultRepo.getSources()
                val items =
                    try {
                        vaultRepo.listItems()
                    } catch (_: Exception) {
                        emptyList()
                    }
                _uiState.update {
                    it.copy(
                        sources = sources,
                        items = items,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Failed to load vault status",
                    )
                }
            }
        }
    }

    fun toggleSource(
        source: VaultSource,
        enabled: Boolean,
    ) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isActionInProgress = true) }
            try {
                vaultRepo.setSourceEnabled(source.name, enabled)
                val sources = vaultRepo.getSources()
                _uiState.update { it.copy(sources = sources, isActionInProgress = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        toastMessage = "Failed to update ${source.displayName}: ${e.message}",
                    )
                }
            }
        }
    }

    fun unlockSource(
        name: String,
        password: String,
    ) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isActionInProgress = true, unlockDialogSource = null) }
            try {
                val success = vaultRepo.unlockSource(name, password)
                val sources = vaultRepo.getSources()
                val items =
                    try {
                        vaultRepo.listItems()
                    } catch (_: Exception) {
                        emptyList()
                    }
                _uiState.update {
                    it.copy(
                        sources = sources,
                        items = items,
                        isActionInProgress = false,
                        toastMessage = if (success) "Unlocked $name" else "Failed to unlock $name",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        toastMessage = "Error unlocking $name: ${e.message}",
                    )
                }
            }
        }
    }

    fun lockSource(name: String? = null) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isActionInProgress = true) }
            try {
                vaultRepo.lockSource(name)
                val sources = vaultRepo.getSources()
                val items =
                    try {
                        vaultRepo.listItems()
                    } catch (_: Exception) {
                        emptyList()
                    }
                _uiState.update {
                    it.copy(
                        sources = sources,
                        items = items,
                        isActionInProgress = false,
                        toastMessage = if (name != null) "Locked $name" else "All password managers locked",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        toastMessage = "Error locking vault: ${e.message}",
                    )
                }
            }
        }
    }

    fun showUnlockDialog(source: VaultSource) {
        _uiState.update { it.copy(unlockDialogSource = source) }
    }

    fun dismissUnlockDialog() {
        _uiState.update { it.copy(unlockDialogSource = null) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
