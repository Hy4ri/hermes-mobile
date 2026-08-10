package com.m57.hermescontrol.theme

/**
 * Chat message rendering style (issue #866). App-local preference,
 * persisted in [com.m57.hermescontrol.data.config.ServerStoreState].
 *
 * BUBBLES — legacy bubble renderer (default, unchanged behavior).
 * FULL_BLEED — agent prose renders directly on the background with turn
 *              headers; user messages keep bubbles (parallel renderer).
 */
enum class ChatStyle { BUBBLES, FULL_BLEED }
