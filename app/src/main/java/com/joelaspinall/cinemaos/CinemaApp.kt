package com.joelaspinall.cinemaos

import android.app.Application
import com.joelaspinall.cinemaos.companion.CompanionServer
import com.joelaspinall.cinemaos.data.Launcher
import com.joelaspinall.cinemaos.data.Letterboxd
import com.joelaspinall.cinemaos.data.Plex
import com.joelaspinall.cinemaos.data.Recommender
import com.joelaspinall.cinemaos.data.Store
import com.joelaspinall.cinemaos.data.Tmdb
import com.joelaspinall.cinemaos.data.Updater
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableSharedFlow

/** Holds the app's single copy of each service. */
class CinemaApp : Application() {
    lateinit var store: Store; private set
    lateinit var tmdb: Tmdb; private set
    lateinit var plex: Plex; private set
    lateinit var letterboxd: Letterboxd; private set
    lateinit var launcher: Launcher; private set
    lateinit var recommender: Recommender; private set
    lateinit var updater: Updater; private set
    lateinit var companion: CompanionServer; private set

    /** Fired when something changes from the phone page, so the TV can refresh. */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 16)

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        tmdb = Tmdb(store)
        plex = Plex(store)
        letterboxd = Letterboxd(store)
        launcher = Launcher(this)
        recommender = Recommender(store, tmdb, plex)
        updater = Updater(this, store)
        companion = CompanionServer(store, letterboxd) { events.tryEmit(it) }
        runCatching { companion.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
    }
}
