package com.bailensn.sort.ui.privacy

import android.content.Context

/** 是否已经读过并同意隐私说明，只在首启问一次。 */
object PrivacyStore {

    private const val PREFS = "sort_privacy"
    private const val KEY_ACCEPTED = "accepted"

    fun accepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPTED, false)

    fun accept(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACCEPTED, true)
            .apply()
    }
}
