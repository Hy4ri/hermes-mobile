package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class DoctorResponse(
    val ok: Boolean,
    val pid: Int?,
    val name: String?,
)
