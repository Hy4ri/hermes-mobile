package com.m57.hermescontrol.data.model

data class MemoryResponse(
    val active: String? = null,
    val builtin_files: BuiltinFileSizes? = null,
)

data class BuiltinFileSizes(
    val memory: Long? = null,
    val user: Long? = null,
)
