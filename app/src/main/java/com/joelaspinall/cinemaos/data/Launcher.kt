package com.joelaspinall.cinemaos.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.serialization.json.JsonObject

/**
 * Opens a film in Netflix / Prime Video / Disney+ / HBO Max / Apple TV.
 * The service's own ID for the film comes from Wikidata (looked up by TMDB id);
 * if there isn't one, the service's app opens at its home screen instead.
 */
class Launcher(private val context: Context) {
    private val pm = context.packageManager
    private val idCache = HashMap<Int, Map<String, String>>()

    data class InstalledApp(val packageName: String, val label: String)

    private val installed: List<InstalledApp> by lazy {
        val tv = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val phone = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        (pm.queryIntentActivities(tv, 0) + pm.queryIntentActivities(phone, 0))
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
    }

    /** The installed app for a service, matched by its name on the home screen. */
    fun appFor(service: Service): InstalledApp? {
        val apps = installed.filter { it.packageName != context.packageName }
        for (want in service.appLabels) {
            apps.firstOrNull { it.label.lowercase() == want }?.let { return it }
        }
        for (want in service.appLabels.filter { it.length > 3 }) {
            apps.firstOrNull { it.label.lowercase().contains(want) }?.let { return it }
        }
        return null
    }

    fun isInstalled(service: Service) = appFor(service) != null

    /** Looks up each service's own ID for this film on Wikidata. */
    suspend fun serviceIds(tmdbId: Int): Map<String, String> {
        idCache[tmdbId]?.let { return it }
        val props = SERVICES.mapNotNull { s -> s.wikidataProp?.let { s.id to it } }
        val vars = props.joinToString(" ") { "?${it.first}" }
        val optionals = props.joinToString(" ") { "OPTIONAL { ?item wdt:${it.second} ?${it.first} . }" }
        val query = "SELECT $vars WHERE { ?item wdt:P4947 \"$tmdbId\" . $optionals } LIMIT 1"
        val url = "https://query.wikidata.org/sparql?format=json&query=" + Uri.encode(query)
        val result = runCatching {
            val row = Http.getJson(url, mapOf("User-Agent" to "CinemaOS/1.0 (personal Android TV app)"), quick = true)
                .obj()?.get("results").obj()?.get("bindings").arr().firstOrNull().obj()
            props.mapNotNull { (id, _) ->
                (row?.get(id) as? JsonObject).str("value")?.let { id to it }
            }.toMap()
        }.getOrDefault(emptyMap())
        idCache[tmdbId] = result
        return result
    }

    /**
     * Opens the film in the service. Returns a short message for the screen
     * when it could only open the app rather than the film itself.
     */
    suspend fun open(service: Service, film: Film): String? {
        val app = appFor(service)
            ?: return "${service.label} isn't installed on this TV."
        val serviceId = film.tmdbId?.let { serviceIds(it)[service.id] }
        if (serviceId != null) {
            val deep = Intent(Intent.ACTION_VIEW, Uri.parse(service.urlFor(serviceId)))
                .setPackage(app.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("source", "30") // Netflix's "opened from another app" hint
            try {
                context.startActivity(deep); return null
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        val launch = pm.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: pm.getLaunchIntentForPackage(app.packageName)
            ?: return "Couldn't open ${service.label}."
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened ${service.label} — search for “${film.title}”."
    }

    fun installedVersion(): String = runCatching {
        pm.getPackageInfo(context.packageName, PackageManager.GET_META_DATA).versionName ?: "?"
    }.getOrDefault("?")
}
