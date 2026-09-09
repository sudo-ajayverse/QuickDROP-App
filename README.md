# QuickDrop Share Target (Android)
A minimal Android **Share Target** that:
1) Receives shared files from the Android share sheet
2) Streams them directly to **Supabase Storage** (no local saving)
3) Inserts a row into your Supabase **`files`** table
4) Shows a tiny “Uploading / Done / Failed” screen and closes
## Behavior
**Share → QuickDrop → Upload starts → Success/Failure → auto-close**
## Non-goals (by design)
- No file browser / no file list
- No permanent local storage
- No login UI
## Supabase setup
Create a public bucket (example: `files`) and ensure your `files` table exists.
### Configure secrets (recommended)
Add to the project-root `local.properties` (next to `sdk.dir=...`).
This file is ignored by git.
```properties
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_ANON_KEY=<your anon key>
SUPABASE_BUCKET=files
SUPABASE_FILES_TABLE=files
```
The app reads these from (in order):
1) `local.properties`
2) Gradle properties (`-P...` / `~/.gradle/gradle.properties`)
3) Environment variables
### What the app calls
- Upload (streaming):
  `PUT {SUPABASE_URL}/storage/v1/object/{bucket}/{objectKey}`
- Insert row:
  `POST {SUPABASE_URL}/rest/v1/{filesTable}`
Default inserted columns:
- `name` (text)
- `url` (text)
- `size` (optional; if your table doesn’t have it, the app retries without it)
## Build
### Debug (for testing)
```powershell
./gradlew.bat :app:assembleDebug
```
### Signed Release APK (for sharing/installing)
Android Studio: **Build → Generate Signed Bundle / APK… → APK**
Output:
`app/build/outputs/apk/release/app-release.apk`
## Notes
- Uses `ContentResolver.openInputStream(uri)` + OkHttp streaming (no disk writes).
- Uses `WorkManager` (`CoroutineWorker`) to avoid blocking the UI thread.



.
