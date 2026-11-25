package de.rb64.live.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Genauen Standort anfragen - zusammen mit dem ungefaehren, wie Android 12+ es verlangt (sonst wird die Anfrage
 * auf manchen Versionen still ignoriert). Ergebnis: true, wenn der genaue Standort erlaubt ist.
 * Der Eingabewert wird nicht gebraucht (passt zu launch(ACCESS_FINE_LOCATION) wie bisher).
 */
class FineLocation : ActivityResultContract<String, Boolean>() {
    private val inner = ActivityResultContracts.RequestMultiplePermissions()
    private val both = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    override fun createIntent(context: Context, input: String): Intent = inner.createIntent(context, both)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        inner.parseResult(resultCode, intent)[Manifest.permission.ACCESS_FINE_LOCATION] == true

    override fun getSynchronousResult(context: Context, input: String): SynchronousResult<Boolean>? =
        inner.getSynchronousResult(context, both)?.let { SynchronousResult(it.value[Manifest.permission.ACCESS_FINE_LOCATION] == true) }
}
