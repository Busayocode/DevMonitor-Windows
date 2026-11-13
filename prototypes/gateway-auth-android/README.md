# DevMonitor Gateway — Android Firebase Authentication Adapter

This is an isolated Android library prototype on `devmonitor-5.0-foundation`.
It does not modify the V340 Full edition source ZIP or its Google Drive flow.

## What it does

- Reads the currently signed-in Firebase user from `FirebaseAuth`.
- Calls `FirebaseUser.getIdToken(false)`; Firebase returns a valid cached token
  or refreshes it when needed.
- Adds the result as `Authorization: Bearer <Firebase ID token>`.
- Refuses to sign non-HTTPS requests.
- Does not persist or log the token.
- Does not use Google Drive OAuth access tokens as controller credentials.

## What it does not do yet

- It is not wired into a complete Gateway Android application.
- It does not choose or hard-code a controller URL.
- It does not register tenants, submit device batches, or read cached results.
- It has not been tested against a live Firebase account or deployed controller.

The existing Fieldpulse Firebase project ID is `fieldpulse-b3eb3`. The Gateway app
must be registered as its own Android app in Firebase if it uses a distinct
application ID. The correct `google-services.json` must then be added to the
Gateway app module (not this reusable library). Do not copy the Full edition's
package registration blindly if the Gateway application ID differs.

## Build and test

From this directory, with Android SDK installed:

```sh
gradle :gateway-auth-android:testDebugUnitTest
gradle :gateway-auth-android:assembleDebug
```

The unit tests verify bearer-header construction and that HTTP requests are
rejected before a token is requested.

## Integration contract

After Firebase sign-in succeeds in the Gateway app, create
`FirebaseAuthIdTokenProvider()` and pass it to
`FirebaseBearerRequestSigner`. Sign each controller request immediately
before sending it. Do not save the ID token in preferences, a database, or logs.
The controller must continue to verify signature, issuer, audience, expiry,
issued-at time, authentication time, and UID against the configured Firebase
project.


## Gateway Android application prototype

A separate `:gateway-app` module now demonstrates the intended Gateway sign-in path:

1. Google Sign-In returns a Google ID token for the registered Gateway Android app.
2. The app exchanges that token with Firebase Authentication using
   `GoogleAuthProvider`.
3. The app calls `FirebaseUser.getIdToken(forceRefresh)` through
   `FirebaseAuthIdTokenProvider`.
4. The controller client attaches that Firebase ID token as a bearer token to
   `GET /v1/client/gateways`, and refuses non-HTTPS base URLs.

The app has **no SEND_SMS, RECEIVE_SMS, or READ_SMS permissions**. It is the
ordinary Gateway client-side app module, not the dedicated SMS gateway-phone
worker app.

### One-time Firebase Console step before real sign-in

The Gateway application ID is `com.aziitech.devmonitor.gateway`, distinct from
the Full edition package. In Firebase Console, open the existing
`fieldpulse-b3eb3` project and:

1. Add an Android app with package
   `com.aziitech.devmonitor.gateway`.
2. Register the SHA-1 signing fingerprints for the Gateway app's debug and
   eventual release signing certificates. Existing fingerprints registered
   only against the Full edition package do not automatically register this
   different Android app.
3. Confirm Google is enabled under Authentication > Sign-in method.
4. Download that new app's `google-services.json` and place it at
   `gateway-app/google-services.json`.

Do not copy the Full edition's JSON file: its Android package registration is
for a different application ID. Do not commit the real JSON file or any
signing private key to the repository.

GitHub Actions creates a disposable, fake Firebase configuration solely to
compile the app. That CI configuration cannot sign in and is not a usable app
configuration.

### Controller connection

The app's controller URL field expects the eventual public HTTPS base URL.
The client calls the existing `GET /v1/client/gateways` route. No controller
URL is hard-coded because the controller is still source-only and has not been
deployed. Until a real HTTPS controller is configured with PostgreSQL and
`FIREBASE_PROJECT_ID=fieldpulse-b3eb3`, a live authenticated request cannot
succeed.

The token itself is never displayed, logged, or persisted by this prototype.
