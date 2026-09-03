# Transcribe Recorder, MVP

A Samsung Voice Recorder style Android app with automatic, background transcription for classes and meetings. No n8n, the app talks directly to Groq's API.

## What's included

- Foreground recording service with a live waveform, pause/resume, and bookmarks
- Room database for recordings and timestamped transcript lines
- WorkManager job that runs after recording stops: chunks long audio, uploads to Groq Whisper, stitches timestamps, then asks Groq's Llama model for a summary
- Home screen with search, a recording screen with a Samsung style scrolling waveform, and a detail screen with tap-to-seek transcript and a summary tab

## Setup

### Option A: You have a laptop with Android Studio
1. Open this folder in Android Studio (Koala or newer).
2. Get a free Groq API key at https://console.groq.com
3. Open `gradle.properties` at the project root and paste your key into `GROQ_API_KEY=""`.
4. Sync Gradle, then run on a device or emulator running Android 8.0 (API 26) or higher.

### Option B: Mobile only, build the APK in the cloud with GitHub Actions
No laptop needed, this builds the app on GitHub's servers and hands you a downloadable APK.

1. On your phone, create a GitHub account if you don't have one, then create a new repository (GitHub app or github.com in your browser).
2. Upload this entire `TranscribeRecorder` folder to that repository. Easiest way on mobile: use the GitHub app's "Add file > Upload files" on the repo, or the "Working Copy" / "Termux + git" route if you want the folder structure preserved exactly.
3. In the repo, go to **Settings > Secrets and variables > Actions**, add a new secret named `GROQ_API_KEY` with your Groq key as the value. This keeps the key out of the code itself.
4. Go to the **Actions** tab, you should see a workflow called "Build debug APK" (it's already included at `.github/workflows/build-apk.yml`). Tap it, then tap "Run workflow".
5. Wait 3 to 5 minutes for it to finish, then open the completed run and download the `app-debug-apk` artifact. GitHub gives you a zip containing `app-debug.apk`.
6. On your phone, open that APK file from your Downloads. Android will ask you to allow installing from this source the first time, allow it, then install.
7. Open the app, grant microphone and notification permissions when asked, and you're recording.

Every time you push a change to the `main` branch, the workflow runs again automatically and produces a fresh APK.

## What's not wired up yet (by design, this is the MVP)

- Speaker diarization is not included. The `TranscriptLine` model already has a `speakerLabel` field ready for it, add a pyannote based service later and merge its output by matching timestamps.
- Vocabulary hints are read from `SettingsStore` but there's no settings screen yet to type them in, add a simple text field on a Settings screen that calls `SettingsStore.setVocabularyHints()`.
- The FFmpeg chunking only triggers past 24MB, so short recordings skip it entirely, which is correct behavior, not a bug.
- Export to .txt/.pdf and full-text search (FTS4) are not in this pass, `RecordingDao.search()` currently does a plain `LIKE` query, which is fine for an MVP's data volume.

## Project layout

```
app/src/main/java/com/habeeb/transcriberecorder/
  MainActivity.kt              navigation + permission requests
  data/Entities.kt              Recording, TranscriptLine, DAOs
  data/AppDatabase.kt           Room database
  network/GroqApi.kt            Whisper transcription + Llama summary calls
  recording/RecorderRepository.kt  live amplitude bridge, service -> UI
  recording/RecordingService.kt    foreground recording service
  recording/TranscriptionWorker.kt background chunk/upload/stitch job
  ui/HomeScreen.kt, RecordingScreen.kt, DetailScreen.kt, Theme.kt
```
