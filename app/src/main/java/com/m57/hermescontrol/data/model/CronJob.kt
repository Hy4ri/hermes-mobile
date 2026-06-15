package com.m57.hermescontrol.data.model

data class CronJob(
    val id: String,
    val name: String,
    val schedule: String?,
    val state: String?,
    val last_run_status: String?,
    val next_run: String?,
)
