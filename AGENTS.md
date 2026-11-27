# FireFrame project guidance

## Adding wallpapers

- Drop the source image into `app/assets/wallpapers` and run `./scripts/add-wallpaper.sh "filename.jpg"` from the project. The script registers the wallpaper, builds the APK, and can install it and select it on the Firestick.
- Preserve the image's appearance. Resize or convert to JPEG for packaging as needed, and let FireFrame's backdrop handle legibility. Make visual adjustments only if the user asks for them.
- When installing for a visual check, inspect the resulting TV screenshot with weather, time, date, and the app row visible.
