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

## Firebase Console work still required for a real Gateway APK

1. Open the existing **FieldPulse** project, project ID `fieldpulse-b3eb3`.
2. Keep the existing Google provider enabled under **Authentication → Sign-in method**. Do not create a second Firebase project for this prototype.
3. Under **Project settings → Your apps**, add an Android app with package name exactly `com.aziitech.devmonitor.gateway`.
4. Add the SHA-1 fingerprints for the certificate(s) that will sign this Gateway app:
   - the debug certificate used by the Android phone build;
   - the release certificate if/when a release build is prepared.
   Add SHA-256 as well if Firebase requests it. Fingerprints registered only against `com.aziitech.simregmonitor` do not automatically register the distinct Gateway package.
5. Download the configuration file for this newly registered Gateway Android app. It must identify project `fieldpulse-b3eb3` and package `com.aziitech.devmonitor.gateway`.
6. Place it locally at `prototypes/gateway-auth-android/gateway-app/google-services.json`. Do not use the Full edition's JSON file, and do not commit the real file or signing private keys to Git.

The prototype obtains `default_web_client_id` from this app-specific Google Services configuration. If Google sign-in reports a missing ID token, check the Web OAuth client generated/associated with this same Firebase project and app registration; do not substitute a Drive OAuth client.

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
