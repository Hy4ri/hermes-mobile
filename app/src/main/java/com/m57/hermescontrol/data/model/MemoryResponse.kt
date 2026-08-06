package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class MemoryResponse(
    val active: String? = null,
    val builtin_files: BuiltinFileSizes? = null,
    val providers: List<MemoryProviderStatusRow> = emptyList(),
)

@Serializable
data class BuiltinFileSizes(
    val memory: Long? = null,
    val user: Long? = null,
)
