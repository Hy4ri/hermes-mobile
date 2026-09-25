package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class BusySendMode {
    CORRECT,
    QUEUE,
    GUIDE,
    INTERRUPT,
}
