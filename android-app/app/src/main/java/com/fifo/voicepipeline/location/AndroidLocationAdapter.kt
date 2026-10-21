package com.fifo.voicepipeline.location

import android.content.Context
import com.fifo.voicepipeline.ui.model.CurrentLocationInfo

/**
 * Production implementation of [LocationAdapter].
 *
 * Delegates to the existing [FifoLocationHelper] singleton for all GPS,
 * geocoding, POI-search and map-intent work.  This class is the ONLY place
 * in the codebase that is allowed to hold a reference to an Android [Context]
 * for location purposes; every caller above this layer receives only a
 * [LocationAdapter].
 */
class AndroidLocationAdapter(private val context: Context) : LocationAdapter {

    // ── Location resolution ───────────────────────────────────────────────

    override suspend fun getFreshLocation(): CurrentLocationInfo =
        FifoLocationHelper.getFreshLocation(context)

    override fun getCurrentLocation(): CurrentLocationInfo =
        FifoLocationHelper.getCurrentLocation(context)

    // ── Geocoding ─────────────────────────────────────────────────────────

    override fun reverseGeocode(latitude: Double, longitude: Double): Pair<String, String>? =
        FifoLocationHelper.reverseGeocodeDetailed(context, latitude, longitude)

    override fun getSpokenRouteGuidance(destination: String): SpokenRouteGuidance =
        FifoLocationHelper.getSpokenRouteGuidance(context, destination)

    // ── POI search ────────────────────────────────────────────────────────

    override suspend fun searchNearbyPlaces(
        query: String,
        currentLocation: CurrentLocationInfo?
    ): List<NearbyPlaceMatch> =
        FifoLocationHelper.searchNearbyPlaces(context, query, currentLocation)

    // ── Navigation intents ────────────────────────────────────────────────

    override fun startExternalNavigation(
        destination: String,
        app: String
    ): Pair<Boolean, String> =
        FifoLocationHelper.startNavigation(context, destination, app)

    override fun openMapPin(latitude: Double, longitude: Double, label: String) =
        FifoLocationHelper.openMapPin(context, latitude, longitude, label)

    // ── Bearing helpers ───────────────────────────────────────────────────

    override fun calculateCardinal(bearing: Float): String =
        FifoLocationHelper.calculateCardinal(bearing)
}
