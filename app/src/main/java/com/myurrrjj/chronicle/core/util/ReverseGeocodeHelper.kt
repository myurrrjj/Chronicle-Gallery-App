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

sealed interface GeocodeResult {
    data class Success(val place: PlaceCacheEntity) : GeocodeResult
    data object OfflineOrUnavailable : GeocodeResult
    data object NotFound : GeocodeResult

}


@Singleton
class ReverseGeocodeHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun resolveBucket(
        bucketKey: String, latitude: Double, longitude: Double
    ): GeocodeResult {
        if (!Geocoder.isPresent()) return GeocodeResult.OfflineOrUnavailable
        return when (val lookup = fetchAddress(latitude, longitude)) {
            is AddressLookup.Failed -> GeocodeResult.OfflineOrUnavailable
            AddressLookup.Empty -> GeocodeResult.NotFound
            is AddressLookup.Found -> {
                val address = lookup.address
                val city = address.locality ?: address.subAdminArea ?: address.featureName
                val state = address.adminArea
                val country = address.countryName
                val displayName = listOfNotNull(city, state ?: country).distinct()
                    .joinToString(", ")
                if (displayName.isBlank()) {
                    GeocodeResult.NotFound
                } else {
                    GeocodeResult.Success(
                        PlaceCacheEntity(
                            bucketKey = bucketKey,
                            city = city,
                            state = state,
                            country = country,
                            displayName = displayName,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    private sealed interface AddressLookup {
        data class Found(val address: Address) : AddressLookup
        data object Empty : AddressLookup
        data object Failed : AddressLookup
    }

    private suspend fun fetchAddress(lat: Double, lng: Double): AddressLookup {
        val geocoder = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    geocoder.getFromLocation(
                        lat, lng, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (!cont.isActive) return
                                val first = addresses.firstOrNull()
                                cont.resume(
                                    if (first != null) AddressLookup.Found(first)
                                    else AddressLookup.Empty
                                )
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(AddressLookup.Failed)
                            }
                        })
                }.onFailure {
                    if (cont.isActive) cont.resume(AddressLookup.Failed)
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                val address = runCatching { geocoder.getFromLocation(lat, lng, 1)?.firstOrNull() }
                    .getOrNull()
                if (address != null) AddressLookup.Found(address) else AddressLookup.Empty
            }
        }
    }

}