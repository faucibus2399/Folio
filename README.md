# Folio — EPUB reader for Android

A simple, offline EPUB reader with vertical scroll reading.

## Features
- **Library**: add EPUBs from your phone (or open them straight from a file manager / Downloads). Shows covers, author and reading progress. Long-press a book to remove it.
- **Scroll reading**: each chapter scrolls top to bottom. "Next chapter" / "Previous chapter" buttons sit at the end of each chapter, and you can also use the arrows or chapter slider in the bottom bar.
- **Pull to change chapter**: at the bottom of a chapter, keep pulling up until the bubble says "Release for next chapter", then let go. At the top, pull down to go back to the end of the previous chapter. You'll feel a small buzz when it's ready. This can be turned off in Aa → More.
- **Tap the page** to show or hide the menu (the reader goes fullscreen when the menu is hidden).
- **Chapters**: the list icon at the top right opens the book's table of contents, with the current chapter highlighted.
- **Bookmarks**: the bookmark icon saves your exact spot along with a short text snippet. Tap it again on the same spot to remove it. The stacked-bookmarks icon lists them all; long-press one to delete it.
- **Text settings ("Aa")**, previewed live while the panel is open:
  - Themes: Light, Sepia, Dark, Black (AMOLED night mode), Auto (follows the phone's dark mode)
  - Text size, line spacing, paragraph spacing, side margins, letter spacing
  - Font: book default, Serif, Sans-serif, Condensed, Monospace, or **import your own .ttf/.otf font** (for example OpenDyslexic or Literata)
  - Alignment (book default / left / justify), hyphenation
  - Brightness override, keep screen on, volume buttons scroll the page
- Remembers your position in every book.

## Kindle books
These aren't supported. Kindle store books (AZW/KFX) are DRM-encrypted, so only Amazon's apps can open them. If you add a Kindle file, Folio tells you that clearly. DRM-free MOBI files can be converted to EPUB with the free [Calibre](https://calibre-ebook.com) app and then read here.

## Building the APK

### Option A: Android Studio (on a computer)
1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** and choose this `Folio` folder. Let Gradle sync; it downloads everything it needs.
3. Plug in your phone with USB debugging on and press ▶ Run.
   Or use **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, then copy `app/build/outputs/apk/debug/app-debug.apk` to your phone and install it.

### Option B: GitHub (no Android Studio needed)
1. Create a new GitHub repository and upload this folder's contents, including the hidden `.github` folder.
2. Open the **Actions** tab. The "Build APK" workflow runs automatically.
3. When it finishes, download the **Folio-apk** artifact, unzip it, and install `app-debug.apk` on your phone. You'll need to allow "Install unknown apps".

Requirements: Android 8.0+.
