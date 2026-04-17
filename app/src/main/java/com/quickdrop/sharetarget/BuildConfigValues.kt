package com.quickdrop.sharetarget

/**
 * Helper for accessing generated BuildConfig values without referencing the BuildConfig symbol.
 *
 * Why: some IDE/static analyzers (and lightweight compilation checks) don't see the generated
 * BuildConfig class, but the Android build will generate it at compile time.
 */
object BuildConfigValues {

    fun getString(fieldName: String, defaultValue: String = ""): String {
        return runCatching {
            val clazz = Class.forName("com.quickdrop.sharetarget.BuildConfig")
            val field = clazz.getField(fieldName)
            field.get(null)?.toString() ?: defaultValue
        }.getOrDefault(defaultValue)
    }
}

