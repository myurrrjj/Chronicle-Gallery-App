package com.myurrrjj.chronicle.core.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.myurrrjj.chronicle.data.local.entity.PlaceCacheEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class ReverseGeocoderHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun resolveBucket(
        bucketKey: String,
        latitude: Double,
        longitude: Double
    ): PlaceCacheEntity? {
        if (!Geocoder.isPresent()) return null
        val address = fetchAddress(latitude, longitude) ?: return null
        val city = address.locality ?: address.subAdminArea ?: address.featureName
        val state = address.adminArea
        val country = address.countryName
        val displayName = listOfNotNull(city, state ?: country)
            .distinct()
            .joinToString(", ")
            .ifBlank { country ?: return null }

        return PlaceCacheEntity(
            bucketKey = bucketKey,
            city = city,
            state = state,
            country = country,
            displayName = displayName,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private suspend fun fetchAddress(lat: Double, lng: Double): Address? {
        val geocoder = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    geocoder.getFromLocation(
                        lat,
                        lng,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (cont.isActive) cont.resume(addresses.firstOrNull())
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(lat, lng, 1)?.firstOrNull() }.getOrNull()
            }
        }
    }
}