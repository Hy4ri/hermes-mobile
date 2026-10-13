package com.m57.hermescontrol.ui.chat

/** Session-owned, transient gateway notification; never persisted as a transcript message (#1563). */
data class GatewayNoticeUi(
    val key: String,
    val text: String,
    val level: String,
    val kind: String,
)
