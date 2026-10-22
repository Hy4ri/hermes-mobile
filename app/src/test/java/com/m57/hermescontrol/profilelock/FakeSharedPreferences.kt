package com.m57.hermescontrol.profilelock

import android.content.SharedPreferences

/** In-memory [SharedPreferences] with checked commits; [failCommits] simulates a disk write failure. */
internal class FakeSharedPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    var failCommits = false

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = if (values.containsKey(key)) values[key] as String? else defValue

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = throw UnsupportedOperationException()

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = if (values.containsKey(key)) values[key] as Int else defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = if (values.containsKey(key)) values[key] as Long else defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = throw UnsupportedOperationException()

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = if (values.containsKey(key)) values[key] as Boolean else defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(
            key: String,
            value: String?,
        ) = apply { pending[key] = value }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ) = apply { pending[key] = values }

        override fun putInt(
            key: String,
            value: Int,
        ) = apply { pending[key] = value }

        override fun putLong(
            key: String,
            value: Long,
        ) = apply { pending[key] = value }

        override fun putFloat(
            key: String,
            value: Float,
        ) = apply { pending[key] = value }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) = apply { pending[key] = value }

        override fun remove(key: String) = apply { pending[key] = null }

        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            // Like Android, the in-memory map changes even when the disk write fails.
            if (clear) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return !failCommits
        }

        override fun apply() {
            commit()
        }
    }
}
