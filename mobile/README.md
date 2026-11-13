# ZSCMST My Library: patron app (iOS and Android)

The patron portal as a phone app. It is written in **TypeScript with React Native (Expo)**, the same language and React
style as the web frontend, and one codebase builds both the **iOS** and the **Android** app.

## What patrons can do

| Tab | |
|---|---|
| **Loans** | Greeting, notices (blocks, reservations ready), borrowed / overdue / fines figures, loans with due dates, **Renew**, renewal requests and their answers |
| **Reserved** | Reservations with status (waiting, place in line, ready, pick up by), **Cancel** |
| **Search** | Search the catalog, copies available, **Reserve** |
| **Fines** | Fines with paid / unpaid / waived and receipt numbers; payment is at the cashier |
| **My ID** | The library ID with photo and a **scannable Code 128 barcode** (same as the printed ID), **Change PIN**, link to the web portal, **Sign out** |

Sign-in is with the **Library ID and PIN**, like the web portal. "Forgot your PIN?" opens the web page for it. E-books,
booking a pickup date, the ID photo and the data download stay in the web portal (the app links to it).

**Security:** the sign-in tokens are kept in the phone's secure storage (iOS Keychain, Android Keystore). The app uses the
same API, roles and lockouts as the web portal, refreshes the session the same way, and signing out ends the session on
the server. Staff accounts can't sign in to the app.

## Which server it talks to

By default, the hosted library site `https://zscmst-library.pg1992-gp.workers.dev`. For another server (e.g. the campus
server), build with:

```
EXPO_PUBLIC_API_URL=https://library.zscmst.edu.ph
```

(set it in the shell, in a `.env` file in this folder, or as an EAS environment variable). `src/config.ts` reads it.

## Try it on a phone (no build needed)

1. On the phone, install **Expo Go** (App Store / Google Play).
2. On the PC, in this folder: `npm install` once, then `npx expo start`.
3. Scan the QR code with the phone's camera (iPhone) or with Expo Go (Android). The phone and PC must be on the same Wi-Fi,
   or run `npx expo start --tunnel`.

Code changes reload on the phone at once.

## Build the real apps (EAS Build, in the cloud: no Mac or Android Studio needed)

One-time:

1. A free **Expo account** (expo.dev), then `npx eas-cli@latest login` and `npx eas-cli@latest init` (links the project).
2. For the stores:
   - **Google Play Console**: USD 25 once.
   - **Apple Developer Program**: USD 99 a year (for a school, enroll as an organization with its D-U-N-S number).

Builds:

| Goal | Command | Result |
|---|---|---|
| Android test app to install directly | `npx eas-cli@latest build -p android --profile preview` | an **.apk** link; open it on Android phones |
| Store builds | `npx eas-cli@latest build -p all --profile production` | Android **.aab** and iOS **.ipa** |
| Send to the stores | `npx eas-cli@latest submit -p android` / `-p ios` | uploads to Google Play / App Store Connect |

EAS creates and keeps the signing keys. The app ID is `ph.edu.zscmst.library` on both platforms (`app.json`); it can't be
changed after the first store release.

**Store listing** also needs: screenshots, a short description, a privacy policy link (the library's `/privacy` page), and
the data-safety / privacy answers (the app handles the patron's name, library ID, loans and fines, sends them only to the
library's server, and doesn't track or advertise).

## Updating

Change the code, raise `"version"` in `app.json` for a store release, and build again. Small fixes in the TypeScript
code can be pushed without a store review using `npx eas-cli@latest update`.

## Project layout

```
App.tsx              sign-in gate and the bottom tabs
src/api.ts           API client: sign-in, secure token storage, refresh, sign-out
src/config.ts        server address
src/code128.ts       barcode for the library ID (matches the printed ID's Code 128)
src/screens/*.tsx    Loans (home), Reserved, Search, Fines, My ID, Sign-in
src/ui.tsx           buttons, cards, badges, colours
assets/              icon and splash (the school seal)
```

Checks: `npx tsc --noEmit` and `npx expo-doctor`.
