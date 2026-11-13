package com.example.service.ai

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

private const val TAG = "AppCheckInit"

/**
 * Initializes App Check before any Firebase product SDK is used.
 * Debug builds use Firebase's debug provider; release builds use Play Integrity.
 *
 * The debug provider prints its generated token to Logcat. Register that token
 * in Firebase Console > App Check > Manage debug tokens. Do not hard-code or
 * persist tokens using private Firebase SDK preference keys.
 */
fun configureAppCheck(context: Context) {
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
