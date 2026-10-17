# NexStream Player

Premium native Android IPTV/OTT player with original identity and device-based activation.

## What is included (current scaffold)

- **Android App** (`apps/android/`)
  - Kotlin + Jetpack Compose
  - Shows generated Device ID (MAC-style) + Device Key on launch
  - Prepared for Android TV (LEANBACK_LAUNCHER)
  - Media3 dependency included

- **Backend API** (`backend/`)
  - Simple Express server
  - Endpoints: register device, check status, add/list playlists
  - In-memory storage (ready to connect to Supabase/PostgreSQL)

- **Activation Website** (`web/index.html`)
  - Login with Device ID + Device Key
  - Add M3U or Xtream Codes playlist
  - Check device status

## How to run locally

### Backend
```bash
cd backend
npm install
npm start
```

### Website
Open `web/index.html` in browser (points to localhost:3000)

### Android
Open `apps/android/` in Android Studio and run.

## Next steps (planned)

1. Persist data in Supabase
2. Full player screens (Live TV, Movies, Series, EPG)
3. Proper license activation flow with payments
4. Admin panel
5. Production deployment on your VPS

## Repository
https://github.com/StartStatic1/nexstream-player

---

**Status**: Initial working skeleton uploaded. Ready for continued development.