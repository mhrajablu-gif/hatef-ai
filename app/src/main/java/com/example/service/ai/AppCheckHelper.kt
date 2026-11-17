package com.example.service.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

/**
 * Initializes App Check before any Firebase product SDK is used.
 * Debug builds use Firebase's debug provider; release builds use Play Integrity.
 *
 * Existing registered debug tokens remain in the SDK's local preferences across
 * app updates. If AI Studio supplies a token through the launch intent, this
 * compatibility path stores it for the debug provider; no token is embedded in
 * the APK. With no launch token, the SDK reuses its saved token or generates one.
 */
/**
 * Reads the device-local debug secret created/reused by Firebase's debug provider.
 * This is exposed only to the local debug diagnostics screen so the owner can
 * register the exact token in the matching Firebase App Check app. It is never
 * written into BuildConfig, logs, or the APK.
 */
fun getStoredAppCheckDebugToken(context: Context): String? {
    val firebaseApp = FirebaseApp.getApps(context).firstOrNull() ?: return null
    val prefs = context.getSharedPreferences(
        "$DEBUG_STORE_PREFIX${firebaseApp.persistenceKey}",
        Context.MODE_PRIVATE
    )
    return prefs.getString(DEBUG_SECRET_KEY, null)?.takeIf { it.isNotBlank() }
}

fun getFirebaseProjectId(context: Context): String? =
    FirebaseApp.getApps(context).firstOrNull()?.options?.projectId

fun configureAppCheck(context: Context, intent: Intent? = null) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            val initializedApp = FirebaseApp.initializeApp(context)
            if (initializedApp == null) {
                Log.e(TAG, "Firebase is not configured: google-services.json is missing or invalid.")
                return
            }
        }

        val appCheck = FirebaseAppCheck.getInstance()
        if (BuildConfig.DEBUG) {
            // Never package a registered debug token in the APK: this repository's
            // Actions artifacts are publicly downloadable. Preserve AI Studio's
            // launch-intent path for preview builds and leave saved device tokens intact.
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)?.trim()
            if (!debugToken.isNullOrBlank()) {
                val persistenceKey = FirebaseApp.getInstance().persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                if (!prefs.edit().putString(DEBUG_SECRET_KEY, debugToken).commit()) {
                    Log.e(TAG, "Could not persist the configured App Check debug token.")
                } else {
                    Log.d(TAG, "Registered App Check debug token configured.")
                }
            } else {
                Log.i(TAG, "No launch token supplied; the debug provider will reuse a saved token or generate one.")
            }
            appCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.d(TAG, "Debug App Check provider installed.")
        } else {
            appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            Log.d(TAG, "Play Integrity App Check provider installed.")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
