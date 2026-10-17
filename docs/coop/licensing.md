# Licensing, Credits & Branding

> Summary of the upstream rules (`LICENSE.txt`, `docs/recommended-changes.md`, `docs/getting-started-android.md`). It's not legal advice.

## License
- Shattered Pixel Dungeon is **GPLv3** (© Evan Debenham; Pixel Dungeon © Oleg Dolya / Watabou). Our fork is GPLv3 too.
- **If we distribute builds (even free, even to friends), the modified source must be available.** Keep our fork repo public and pushed.
- The Go server is our own code. GPLv3 (not AGPL) doesn't force us to publish it, but we can if we want.

## Must keep
- `LICENSE.txt` and every GPL/copyright header in upstream files.
- **All existing credits in `AboutScene.java`.** They may be repositioned but not removed (upstream rule).
- The **freesound CC-BY credits** (they're legally required attribution).
- `appVersionCode` must not go below upstream's (922 for v4.0.2).

## Should change (fork hygiene, recommended by upstream)
| What | Where |
|---|---|
| `appName`, `appPackageName` (also separates save dirs from vanilla) | root `build.gradle` |
| Title banner & app icons | `core/src/main/assets/interfaces/banners.png`, `android/src/*/res`, `desktop/src/main/assets/icons`, `ios/assets` |
| Update checker (points at upstream GitHub) | switch to `debugUpdates` in `desktop/` and `android/` `build.gradle`, or repoint `GitHubUpdates.java` |
| News feed (shatteredpixel.com) | remove `add(btnNews)` in `TitleScene` or repoint `ShatteredNews.java` |
| Supporter/Patreon button & Goo nag | `TitleScene` (`btnSupport`), `SupporterScene`, `WndSupportPrompt`, `WornKey` (upstream politely asks to keep a Patreon reference in credits) |
| Translations (optional) | `Languages.java`, `messages/*_xx.properties`, `WndSettings` |

## Privacy
- Upstream has **no analytics, tracking or crash reporting**. Its only network calls are news and update checks.
- Android permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `VIBRATE`.
- Our server sees IP addresses. Write a short privacy note before distributing (it's required anyway if we ever use Google Play).

## Stores
- **Google Play:** upstream asks forks to **email Evan@ShatteredPixel.com before publishing** there. Forks get removed for impersonation, and Google removes apps that mention Patreon.
- **iOS:** TestFlight for friends (a public App Store release of a fork is likely to be rejected).
