package com.m57.hermescontrol.data.model

data class DoctorResponse(
    val status: String,
    val reports: List<DoctorReport>,
)

data class DoctorReport(
    val category: String,
    val status: String,
    val message: String,
)
