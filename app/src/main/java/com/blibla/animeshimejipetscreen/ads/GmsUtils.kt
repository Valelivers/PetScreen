package com.blibla.animeshimejipetscreen.ads

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

fun isGooglePlayServicesOk(context: Context): Boolean {
    val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
    return code == ConnectionResult.SUCCESS
}
