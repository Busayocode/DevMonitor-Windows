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
