# TV Launcher

An Android TV home-screen app (icon row, search bar, category chips, and horizontal
media-card rows) built with **plain XML layouts + RecyclerView** — no Jetpack Compose,
no Leanback UI widgets, no bundled third-party logos or screenshots. Every icon, label,
card, and badge on screen comes from one JSON file.

## Why no bundled images

The reference screenshots showed real installed-app icons (Netflix, Prime Video, etc.).
Those are trademarked assets this project doesn't ship. Instead:

- Each item can optionally carry an `iconUrl` (loaded at runtime via Glide).
- If `iconUrl` is empty or the network load fails, the app renders a deterministic
  **letter-avatar** placeholder (first letter of the label on a flat color) — so the UI
  is always fully populated, even offline, with zero bundled art.

Swap in your own icon URLs (your CDN, your app store metadata, whatever) and the layout
is otherwise a drop-in match for the structure in the screenshots: top icon strip →
search bar → "App categories" chip row → card rows ("Apps from my other devices",
"Get started with these games", "Movies + TV", "Stream the music you love").

## Requirements

- Android Studio (Koala/2024.1 or newer recommended)
- JDK 17
- Gradle/AGP resolve from Google's Maven + Maven Central (not reachable from this
  sandbox, so the project hasn't been build-verified here — open it in Android Studio
  and let Gradle sync to pull dependencies)

## Running it

1. Open the project root in Android Studio.
2. Let Gradle sync (pulls AndroidX, Material, Glide, Kotlin coroutines).
3. Run on an **Android TV emulator** (Tools → Device Manager → Create Device → TV
   category) or a physical Android TV / low-end streaming box with USB debugging.
4. The launcher shows up as a regular app (not as your actual home screen) — installing
   it as the *system* leanback launcher requires the device to let you pick a default
   launcher, which varies by manufacturer/skin.

## Architecture

```
MainActivity
  └─ loads assets/home_data.json off the main thread (coroutines)
  └─ hands the parsed HomeData to HomeRowsAdapter (vertical RecyclerView)
       └─ each row inflates item_home_row.xml
            ├─ type="search" → static search bar view
            ├─ type="icons"  → nested horizontal RecyclerView + TopIconsAdapter
            ├─ type="chips"  → nested horizontal RecyclerView + ChipsAdapter
            └─ type="cards"  → nested horizontal RecyclerView + CardsAdapter
```

`HomeRowsAdapter` keeps one shared `RecycledViewPool` per row type (icons/chips/cards),
so scrolling past repeated row types on a low-end TV box doesn't re-inflate identical
item views from scratch.

## JSON schema (`app/src/main/assets/home_data.json`)

```json
{
  "searchHint": "Search for apps and games",
  "rows": [
    {
      "id": "unique-id",
      "title": "Row header, or null to hide it",
      "type": "icons | search | chips | cards",
      "items": [
        {
          "id": "unique-id",
          "label": "Display name",
          "iconUrl": "https://... (optional — falls back to a letter avatar)",
          "badge": "FREE (optional small overlay label)",
          "placeholderColor": "#5C6BC0 (optional — fallback avatar color)"
        }
      ]
    }
  ]
}
```

To point the launcher at a backend instead of the bundled asset, replace the body of
`JsonRepository.load()` with an HTTP fetch of a URL returning the same shape — nothing
else in the app needs to change, since the whole UI is built from the parsed `HomeData`.

`project_manifest.json` at the project root is a machine-readable index of every file
in this repo and what it's for, plus the same JSON schema in one place.

## Low-end Android TV notes

- `minSdk 21` — covers older Lollipop-era TV boxes.
- No Compose (avoids its higher baseline RAM/CPU footprint on cheap SoCs).
- `RecyclerView.itemAnimator = null` on the outer list — skips animation work that tends
  to stutter on weak GPUs.
- Shared `RecycledViewPool`s per row type (see above).
- Glide handles memory/disk caching and downsampling automatically, which matters a lot
  on devices with 512MB–1GB RAM.
- All focus states are done with `Drawable` selectors (`state_focused`) rather than
  ripples/animations, since these devices are driven by a D-pad, not touch.
- `android:hardwareAccelerated="true"` is set; `largeHeap` is deliberately **not** set,
  to stay friendly to low-memory devices.

## What's not included

- Real app icons/logos (see "Why no bundled images" above).
- Actual app-launch intents — `MainActivity.onItemClicked()` currently shows a `Toast`
  with the tapped item's label; wire in your own `Intent`/deep-link logic there.
- A remote content backend — `JsonRepository` reads the bundled asset; see the JSON
  schema section for how to point it at a server instead.
