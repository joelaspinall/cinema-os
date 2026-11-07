# Cinema OS

A Google TV app that puts Joel's Plex library, Netflix, Prime Video, Disney+, HBO Max and Apple TV into one place, with Letterboxd logging and recommendations.

## What it does

- **Home:** Plex "continue watching", recommendations from your Letterboxd ratings (only films on your services or in Plex), "Because you loved…", your watchlist titles that are streaming now, recently added to Plex, your Apple TV purchases, your recent diary, and what's popular on each service.
- **Service tabs:** the full UK catalogue of each service, with genre, sort and "not seen" filters.
- **Film page:** where it's streaming, picture and sound quality for each source (exact for Plex files, "up to" for services based on your plan and what the TV can show), one press to open it in the right app.
- **Logging:** Plex films ask for a rating when they finish. For other services, the app asks "Did you finish it?" when you come back. Ratings build up and are sent to Letterboxd as an import file from the phone page.

## Getting it onto the TV

GitHub builds the app automatically on every change (see the Actions tab). The newest build is on the Releases page as `cinema-os.apk`.

1. On the TV: **Settings → System → About**, then press **Android TV OS build** 7 times to turn on developer options.
2. Install **Downloader** (by AFTVnews) from the Play Store, and allow it to install unknown apps when asked.
3. In Downloader, enter the release link: `https://github.com/joelaspinall/cinema-os/releases/latest/download/cinema-os.apk` (works when the repository is public).
4. Open **Cinema OS**, go to **Settings**, scan the QR code with your phone, and paste your TMDB key there.
5. In **Settings → Plex**, press **Sign in** and enter the code at plex.tv/link.

Later updates: **Settings → Update app** inside Cinema OS.

## Notes

- Keys and sign-ins are saved only on the TV, never in this repository.
- Letterboxd has no public API for writing, so new diary entries go through letterboxd.com/import.
- Streaming services don't publish per-film quality, so the quality shown for them is the best your plan allows.
