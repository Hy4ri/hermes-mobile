package com.m57.hermescontrol.ui.common

/** Fixed mask for a configured secret. The gateway's redacted_value can carry a prefix/suffix, so never show it. */
internal const val KEY_MASK = "••••••••••••"

/** Mask for a secret that is set, or null when it is not set. Ignores any gateway redacted value. */
internal fun maskedSecret(isSet: Boolean): String? = if (isSet) KEY_MASK else null
