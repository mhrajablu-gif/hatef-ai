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
 * The debug provider prints its generated token to Logcat. Register that token
 * in Firebase Console > App Check > Manage debug tokens. Do not hard-code or
 * persist tokens using private Firebase SDK preference keys.
 */
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
            // CI-built APKs do not receive AI Studio's launch intent. Prefer the
            // registered token injected from CI, while retaining preview support.
            val configuredToken = BuildConfig.FIREBASE_APPCHECK_DEBUG_TOKEN.trim()
            val intentToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)?.trim()
            val debugToken = configuredToken.takeIf { it.isNotBlank() } ?: intentToken
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
                Log.e(TAG, "No App Check debug token was supplied by CI configuration or launch intent.")
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
