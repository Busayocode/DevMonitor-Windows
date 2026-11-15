# DevMonitor 5.0 — Firebase Authentication Completion Checklist

**Firebase project:** `fieldpulse-b3eb3` (FieldPulse)  
**Branch:** `devmonitor-5.0-foundation`  
**Gateway Android prototype application ID:** `com.aziitech.devmonitor.gateway`  
**Existing Full edition application ID:** `com.aziitech.simregmonitor`

This checklist is for the Gateway prototype only. It must not replace or disturb the existing Full edition Firebase registration, Google Drive OAuth configuration, Firestore rules, update-check authorization, or payment/MOM data.

## Verified in source

- The Gateway Android prototype exchanges a Google ID token for a Firebase credential using `GoogleAuthProvider`.
- It obtains a Firebase ID token from the signed-in Firebase user.
- It sends that ID token as an HTTPS bearer token; it does not use a Google Drive OAuth access token.
- The controller verifies Firebase JWT signature, issuer, audience, expiry, issued-at time, authentication time and UID.
- Controller unit tests include valid Firebase-token verification and wrong-project rejection.
- The controller reads its expected project from `FIREBASE_PROJECT_ID`.
- CI uses a disposable fake Firebase configuration for compilation only. It cannot sign in to a real account.
- The ordinary Gateway client app manifest declares INTERNET only; it has no SMS send/receive/read permissions.

## Firebase Console setup — current prototype branch

The separate Android app registration for `com.aziitech.devmonitor.gateway` has been created in the existing `fieldpulse-b3eb3` project. The Gateway signing fingerprint was registered, and the app-specific `google-services.json` is present on the isolated `devmonitor-5.0-gateway-android-download` branch. GitHub Actions run #43681725883 verified the package in that file and successfully built the debug APK.

Do not substitute the Full edition's `com.aziitech.simregmonitor` configuration. Do not commit service-account private keys, signing private keys or gateway installation bearer credentials. Keep the Firebase client API key's platform/API restrictions appropriate for the project.

## Controller configuration still required before an end-to-end request

Set the deployed controller environment variable to:

```env
FIREBASE_PROJECT_ID=fieldpulse-b3eb3
```

The controller also requires its dedicated PostgreSQL `DATABASE_URL`. Do not point it at an existing Firebase/Google Drive database. The service must be deployed behind HTTPS before the Gateway app's authenticated controller request can work.

## Real-device verification sequence

1. Build the Gateway app locally with its app-specific `google-services.json`.
2. Sign in using a Google account already allowed to use the FieldPulse Firebase project.
3. Confirm Firebase sign-in succeeds and a fresh Firebase ID token can be obtained. The app must not display, save or log the token.
4. Once the controller is deployed, set its HTTPS base URL in the app and call `GET /v1/client/gateways`.
5. Confirm the controller accepts a token from `fieldpulse-b3eb3` and maps its Firebase UID to one `dm_users.auth_subject` row.
6. Confirm a token from a different Firebase project is rejected.

## What is not being changed

- The Full edition's existing Firebase app registration and Google sign-in.
- Google Drive OAuth, backup, online report access or existing folder configuration.
- Existing Firestore/Realtime Database selection or security rules.
- Update-check, partner authorization, payment or MOM logic.
- The main branch or production application source.

**Important:** Firebase project settings and downloaded app configuration cannot be verified from the repository. A successful CI compile with its dummy JSON is not proof of live Firebase configuration. The real app-specific JSON and live sign-in test are the remaining external verification steps.


## Status update — 15 November 2026

The separate Gateway Android app registration and app-specific `google-services.json` are now present on the isolated `devmonitor-5.0-gateway-android-download` branch for package `com.aziitech.devmonitor.gateway`. The registered signing fingerprint was checked against the phone-generated fingerprint in the preceding setup work. GitHub Actions run #43681725883 passed the package/configuration check, authentication unit tests and debug APK build.

**Still unverified:** a real-device Google sign-in using this distinct Gateway app, retrieval of a live Firebase ID token on that device, and an authenticated request to a deployed HTTPS controller. The CI build does not perform those live operations. The existing Full edition registration and Google Drive OAuth configuration remain separate and unchanged.

The Gateway branch contains the app-specific Firebase client configuration; do not substitute the Full edition's configuration. Never commit service-account private keys, signing private keys or installation bearer credentials.
