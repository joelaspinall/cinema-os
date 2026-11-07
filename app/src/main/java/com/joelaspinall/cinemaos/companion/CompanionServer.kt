package com.joelaspinall.cinemaos.companion

import com.joelaspinall.cinemaos.data.Letterboxd
import com.joelaspinall.cinemaos.data.Store
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * A small web page served by the TV on the home Wi-Fi. Joel scans a QR code with
 * his phone to paste keys, upload his Letterboxd export and download new logs
 * for letterboxd.com/import — no typing with the remote.
 */
class CompanionServer(
    private val store: Store,
    private val letterboxd: Letterboxd,
    private val onChanged: (String) -> Unit,
) : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 8642
    }

    /** Random key in the URL so only someone who scanned the TV's QR code gets in. */
    val accessKey: String = (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789"[SecureRandom().nextInt(31)] }.joinToString("")

    fun address(): String? {
        val ip = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull() ?: return null
        return "http://$ip:$PORT/?k=$accessKey"
    }

    override fun serve(session: IHTTPSession): Response {
        val params = session.parameters
        if (params["k"]?.firstOrNull() != accessKey) {
            return html("<h1>Cinema OS</h1><p>Scan the QR code on the TV (Settings → Phone companion) to open this page.</p>", Response.Status.FORBIDDEN)
        }
        return try {
            when {
                session.method == Method.POST -> handlePost(session)
                session.uri == "/letterboxd-import.csv" -> exportFile()
                else -> page(null)
            }
        } catch (e: Exception) {
            page("Something went wrong: ${esc(e.message ?: e.toString())}")
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val p = session.parameters
        fun v(name: String) = p[name]?.firstOrNull()?.trim().orEmpty()

        val msg = when (session.uri) {
            "/tmdb" -> {
                val token = v("token")
                if (token.length < 20) "That doesn't look like a TMDB key — copy the whole thing."
                else { store.updateSettings { it.copy(tmdbToken = token) }; onChanged("tmdb"); "TMDB key saved. The TV is loading your catalogues." }
            }
            "/letterboxd" -> {
                val user = v("user").removePrefix("@").substringAfter("letterboxd.com/").trim('/')
                store.updateSettings { it.copy(letterboxdUser = user) }; onChanged("letterboxd")
                "Letterboxd username set to $user."
            }
            "/import" -> {
                val path = files["file"] ?: return page("No file received — choose your Letterboxd export first.")
                val name = v("file").ifBlank { "export.zip" }
                val f = File(path)
                val result = if (name.lowercase().endsWith(".csv")) {
                    letterboxd.importCsv(name.substringAfterLast('/'), f.readText())
                } else {
                    f.inputStream().use { letterboxd.importExport(it) }
                }
                onChanged("history")
                "Imported ${result.watched} watched films (${result.rated} rated) and ${result.watchlist} on your watchlist."
            }
            "/apple" -> {
                val text = files["file"]?.let { File(it).readText() }?.takeIf { it.isNotBlank() } ?: v("list")
                val items = parseAppleList(text)
                if (items.isEmpty()) "No films found — upload the apple-tv-films.txt file from your Mac, or paste titles one per line."
                else {
                    store.updateData { it.copy(appleImport = items) }
                    onChanged("apple")
                    "Got ${items.size} films. The TV is matching them now — they'll appear in “Your Apple TV library”."
                }
            }
            "/mark-exported" -> {
                store.markExported(); onChanged("diary")
                "Marked as imported. Next time you'll only get new entries."
            }
            "/services" -> {
                val chosen = p["svc"].orEmpty()
                val plans = com.joelaspinall.cinemaos.data.PLANS.keys.associateWith { v("plan_$it") }.filterValues { it.isNotBlank() }
                store.updateSettings { it.copy(services = chosen, plans = it.plans + plans) }; onChanged("services")
                "Services and plans updated."
            }
            else -> null
        }
        return page(msg)
    }

    /** Accepts "Title<TAB>Year", "Title,Year", "Title (Year)" or just "Title", one per line. */
    private fun parseAppleList(text: String): List<com.joelaspinall.cinemaos.data.HistoryItem> =
        text.lines().map { it.trim() }.filter { it.isNotBlank() && !it.equals("name\tyear", true) }.mapNotNull { line ->
            val tab = line.split('\t')
            val (title, year) = when {
                // Some Apple titles carry the year in the name, e.g. "Drive (2011)".
                tab.size >= 2 -> tab[0].trim().replace(Regex("""\s*\(\d{4}\)$"""), "") to tab[1].trim().take(4).toIntOrNull()
                Regex("""^(.*)\s*\((\d{4})\)\s*$""").matches(line) -> {
                    val m = Regex("""^(.*)\s*\((\d{4})\)\s*$""").find(line)!!
                    m.groupValues[1].trim() to m.groupValues[2].toInt()
                }
                Regex("""^(.*),\s*(\d{4})$""").matches(line) -> {
                    val m = Regex("""^(.*),\s*(\d{4})$""").find(line)!!
                    m.groupValues[1].trim().trim('"') to m.groupValues[2].toInt()
                }
                else -> line to null
            }
            title.takeIf { it.isNotBlank() }?.let { com.joelaspinall.cinemaos.data.HistoryItem(it, year) }
        }.distinctBy { it.title.lowercase() + it.year }

    private fun exportFile(): Response {
        val entries = store.unexported()
        val csv = letterboxd.exportCsv(entries)
        val res = newFixedLengthResponse(Response.Status.OK, "text/csv", csv)
        res.addHeader("Content-Disposition", "attachment; filename=\"cinema-os-letterboxd-${java.time.LocalDate.now()}.csv\"")
        return res
    }

    private fun page(message: String?): Response {
        val s = store.settings.value
        val d = store.data.value
        val k = accessKey
        val pending = d.diary.filter { !it.exported }
        val recent = d.diary.take(25)
        val svcBoxes = com.joelaspinall.cinemaos.data.SERVICES.joinToString("") { svc ->
            val on = if (svc.id in s.services) "checked" else ""
            val plans = com.joelaspinall.cinemaos.data.PLANS[svc.id].orEmpty()
            val current = com.joelaspinall.cinemaos.data.planFor(svc.id, s.plans[svc.id])?.id
            val select = if (plans.size > 1) {
                "<select name=\"plan_${svc.id}\">" + plans.joinToString("") { pl ->
                    "<option value=\"${pl.id}\"${if (pl.id == current) " selected" else ""}>${esc(pl.label)} — ${esc(pl.quality.label)}</option>"
                } + "</select>"
            } else "<span class=dim>${esc(plans.firstOrNull()?.quality?.label ?: "")}</span>"
            """<div class="svc"><label class="chk"><input type="checkbox" name="svc" value="${svc.id}" $on> ${esc(svc.label)}</label> $select</div>"""
        }
        val rows = recent.joinToString("") { e ->
            val stars = e.rating?.let { r -> "★".repeat(r.toInt()) + if (r % 1.0 != 0.0) "½" else "" } ?: "–"
            val link = if (e.tmdbId != null) "https://letterboxd.com/tmdb/${e.tmdbId}/" else "https://letterboxd.com/search/${esc(e.title)}/"
            """<tr><td>${esc(e.title)}${e.year?.let { " <span class=dim>($it)</span>" } ?: ""}</td><td>${e.watchedDate}</td><td class=gold>$stars</td>
               <td>${if (e.exported) "<span class=dim>sent</span>" else "<b>new</b>"}</td><td><a href="$link">Open</a></td></tr>"""
        }
        val body = """
<h1>Cinema <span class=gold>OS</span></h1>
${message?.let { "<div class=msg>${esc(it)}</div>" } ?: ""}

<section>
<h2>1 · Send new logs to Letterboxd</h2>
<p>${pending.size} new ${if (pending.size == 1) "entry" else "entries"} since your last import.</p>
${if (pending.isNotEmpty()) """
<ol>
<li><a class=btn href="/letterboxd-import.csv?k=$k">Download Letterboxd file</a></li>
<li><a class=btn href="https://letterboxd.com/import/" target=_blank>Open letterboxd.com/import</a> and upload that file</li>
<li><form method=post action="/mark-exported?k=$k"><button>Done — mark as imported</button></form></li>
</ol>""" else ""}
${if (rows.isNotEmpty()) "<table><tr><th>Film</th><th>Watched</th><th>Rating</th><th></th><th></th></tr>$rows</table>" else "<p class=dim>Nothing logged in Cinema OS yet.</p>"}
</section>

<section>
<h2>2 · Your Letterboxd history</h2>
<p class=dim>${if (d.history.isNotEmpty()) "${d.history.size} films imported (${d.historyImportedAt ?: ""}), ${d.watchlist.size} on watchlist." else "Not imported yet."}
On Letterboxd: <b>Settings → Data → Export your data</b>, then upload the .zip here.</p>
<form method=post enctype="multipart/form-data" action="/import?k=$k">
<input type=file name=file accept=".zip,.csv"> <button>Upload</button></form>
<form method=post action="/letterboxd?k=$k"><label>Username <input name=user value="${esc(s.letterboxdUser)}"></label> <button>Save</button></form>
</section>

<section>
<h2>3 · TMDB key</h2>
<p class=dim>${if (s.tmdbToken.isNotBlank()) "Key saved ✓" else "Needed for catalogues and recommendations."}
Free at <a href="https://www.themoviedb.org/settings/api" target=_blank>themoviedb.org/settings/api</a> — paste the <b>API Read Access Token</b>.</p>
<form method=post action="/tmdb?k=$k"><input name=token placeholder="Paste key here" autocomplete=off> <button>Save</button></form>
</section>

<section>
<h2>4 · Apple TV purchases</h2>
<p class=dim>${if (d.appleOwned.isNotEmpty()) "${d.appleOwned.size} films in your Apple TV library${d.appleImportedAt?.let { " (updated $it)" } ?: ""}." else "Not imported yet."}
Run the command from Claude on your Mac, then upload <b>apple-tv-films.txt</b> from your Desktop — or paste titles below, one per line.</p>
<form method=post enctype="multipart/form-data" action="/apple?k=$k">
<input type=file name=file accept=".txt,.csv"><br>
<textarea name=list rows=4 placeholder="Dune: Part Two (2024)"></textarea><br>
<button>Add to Apple TV library</button></form>
</section>

<section>
<h2>5 · Your services &amp; plans</h2>
<p class=dim>Your plan sets the best quality the app shows for each service (4K, Dolby Vision, Atmos…).</p>
<form method=post action="/services?k=$k">$svcBoxes<br><button>Save</button></form>
</section>
"""
        return html(body)
    }

    private fun html(body: String, status: Response.Status = Response.Status.OK): Response = newFixedLengthResponse(
        status, "text/html; charset=utf-8",
        """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Cinema OS</title><style>
:root{color-scheme:dark}body{font:16px/1.5 system-ui,sans-serif;background:#0e0e14;color:#f2ede4;margin:0;padding:16px;max-width:720px;margin:auto}
h1{font-size:28px;margin:8px 0 16px}h2{font-size:18px;margin:0 0 8px}section{background:#1a1a24;border-radius:14px;padding:16px;margin:0 0 16px}
.gold{color:#e8aa42}.dim{color:#9a958c}.msg{background:#2b2414;border:1px solid #e8aa42;border-radius:10px;padding:10px 12px;margin-bottom:16px}
.svc{margin:6px 0}select{font:inherit;background:#0e0e14;color:inherit;border:1px solid #3a3a48;border-radius:8px;padding:6px;max-width:100%}
input{font:inherit;background:#0e0e14;color:inherit;border:1px solid #3a3a48;border-radius:8px;padding:8px 10px;max-width:100%}
textarea{font:inherit;width:100%;box-sizing:border-box;background:#0e0e14;color:inherit;border:1px solid #3a3a48;border-radius:8px;padding:8px;margin:8px 0}
input[name=token]{width:100%;box-sizing:border-box;margin-bottom:8px}button,.btn{font:inherit;background:#e8aa42;color:#0e0e14;border:0;border-radius:8px;padding:8px 14px;font-weight:600;text-decoration:none;display:inline-block;cursor:pointer}
ol{padding-left:20px}li{margin:8px 0}form{margin:8px 0}a{color:#e8aa42}.chk{display:inline-block;margin:4px 12px 4px 0}
table{width:100%;border-collapse:collapse;font-size:14px;margin-top:8px}td,th{padding:6px 4px;border-bottom:1px solid #2a2a36;text-align:left}
</style></head><body>$body</body></html>""",
    )

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
