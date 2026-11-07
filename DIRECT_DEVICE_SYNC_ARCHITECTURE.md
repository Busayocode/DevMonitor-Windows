# Direct Android–Windows Device Sync — Trial Architecture

Status: paired-platform source audit; Windows Firebase sign-in and account-bound pairing implementation pending. No sync feature is certified yet.
Branch: `trial/direct-google-account-lan-sync`
Repository: `Busayocode/DevMonitor-Windows`

## User experience target

Install DevMonitor on Android and Windows, sign in to the same Google account in both apps, approve the two devices once, and let them exchange the supported operational data automatically. There must be no separately installed Sync Relay application or manually operated relay server.

The Google account establishes the account identity. Each installation must still have its own device identity and be explicitly paired/authorized; sharing an email address alone is not a safe device-authentication protocol.

## What the current source already does

### Same Wi-Fi / local network

- Windows contains `SyncBridgeServer.cs`, an HTTP listener on port 43827.
- Windows starts the bridge with the desktop app and starts `SyncBridgeDiscoveryResponder.cs` on UDP port 43828.
- Android contains `SyncBridgeDiscovery.discover()`, which broadcasts `DEVMONITOR_DISCOVER_V1` and discovers the Windows app on the LAN.
- Android `SyncBridgeClient.sync()` sends and receives AES-GCM-encrypted operational snapshots.
- Android listens for PC database-change events and watches its own SQLite database for local changes while the relevant foreground screen is active.
- This is direct app-to-app LAN communication. A separate Sync Relay is not required when the phone and PC can reach each other on the same local network.

### Current friction / gaps

- Android currently stores a PC endpoint and a separate pairing key.
- Initial setup uses a QR code/manual discovery and saves the key locally.
- The Android client derives the AES-GCM key as SHA-256 of the saved pairing key. The Windows bridge does the same. The current Windows startup callback auto-approves the first encrypted exchange (`() => Task.FromResult(true)`), so the current approval gate is not a real user/device authorization step.
- The Windows bridge currently listens on `http://+:{port}/` for LAN traffic. Payload encryption exists, but the account-bound peer authentication and authenticated key establishment required for the new design are not present. A static key in app settings plus an auto-approval callback is not an acceptable final pairing design.
- Android v340 already performs Google sign-in and exchanges the Google ID token with `GoogleAuthProvider.getCredential(...)` followed by `FirebaseAuth.signInWithCredential(...)`; it also observes Firebase Auth state. This means Android already has the Firebase identity path needed for account matching. Windows v340 has no equivalent Firebase sign-in yet. The Android Google Drive access-token flow is separate and must not be treated as the Firebase account identity.
- Android's near-real-time sync is currently scoped to an active foreground screen/session; this is not yet a fully background-resilient always-on sync service.
- Direct LAN discovery does not traverse routers or mobile networks. Different-network sync needs a managed cloud transport or a secure peer-to-peer connectivity mechanism. It must not be represented as direct LAN communication.

## Sync Relay decision

The standalone Sync Relay is not needed for same-network phone-to-PC exchange. It exists for remote synchronization when devices are on different networks.

The Windows installer already installs only the Windows app's `publish` directory; the relay is not embedded in that installer. The GitHub workflow previously emitted a third, separate `DevMonitor-Sync-Relay` artifact. This trial removes that artifact from the normal build outputs so users are not led to believe they must install it for local communication. The relay source is retained for now; no remote-sync functionality is being silently deleted.

## Target design

1. Add Google sign-in to Windows using a supported desktop OAuth flow and exchange the Google credential with the same Firebase Authentication project used by Android.
2. Expose the authenticated Firebase UID and per-installation device ID to the sync-pairing layer; never trust a client-supplied email string.
3. On the same LAN, Android discovers Windows automatically. The two authenticated apps compare verified account identity and complete a one-time device authorization handshake.
4. Remove the current auto-approve callback and static-key-only trust model. Derive or negotiate a unique device-pair key using a reviewed authenticated key-agreement protocol. Do not use the Google password, raw ID token, or a static user-wide secret as the AES key. A first-time pairing must show the peer identity on both devices and require explicit approval.
5. Persist the trusted device pairing securely and resume encrypted bidirectional LAN sync automatically when both apps are available.
6. Keep local data usable offline. Use retry/backoff, bounded batches, acknowledgements, conflict handling, deletion semantics, and recovery after sleep/reconnect.
7. Keep the current sync allow-list: do not transfer payment/MOM records, HQ snapshots, targets, or other protected business data without separate explicit approval.
8. Treat remote-network synchronization as a separate optional capability. If later enabled, it should use developer-managed Firebase/cloud infrastructure or another managed transport, never require the user to install a relay program.

## Confirmed Google Cloud/Firebase setup

- Firebase project: `fieldpulse-b3eb3` (project number `276884313547`).
- Android package: `com.aziitech.simregmonitor`.
- Google is enabled as a Firebase Authentication provider (confirmed by the user).
- Android v340 includes Google Sign-In and Firebase Auth dependencies and signs in using a Google ID token exchanged through Firebase Auth. The Android Firebase UID is therefore available from `FirebaseAuth.currentUser.uid` after successful sign-in.
- User-provided Windows Desktop OAuth Client ID: `276884313547-u2f38f1i8veta36acrp7rfviv7ibldoe.apps.googleusercontent.com`.
- Windows must use the desktop client with Authorization Code + PKCE (loopback redirect) and exchange the Google credential with the same Firebase project. Do not embed a client secret. Do not use the Android OAuth client or Web client as a substitute for the desktop client.
- Google Drive OAuth remains a separate authorization flow for Drive backup. Do not reuse its access token as the Firebase identity or as a pairing secret.

## Safety and regression boundaries

- Work only on this trial branch until the user tests and approves it.
- Do not merge to `main` without explicit approval.
- Do not modify payment/MOM formulas, cycle boundaries, target logic, HQ snapshots, or report calculations.
- Preserve AES-GCM confidentiality and validate peer identity before accepting sync payloads.
- Do not mark sync complete until LAN discovery, first pairing, two-way transfer, offline/reconnect, Windows sleep/resume, duplicate suppression, conflict handling, and deletion behavior are tested on real devices.
- Source inspection and a successful compile are not proof of runtime device-to-device behavior.

## Current baseline audit (2026-11-07)

- Android v340 source artifact from successful GitHub Actions run `42391551935` was inspected. It contains the existing Google-to-Firebase sign-in path and the current encrypted LAN bridge client.
- PC v340 source ZIP in the user's Library was inspected. It contains the WPF LAN bridge and UDP discovery responder, but no Firebase/Auth dependency or Windows Google sign-in implementation. The bridge currently auto-approves first exchange and uses a persistent pairing token as the AES-GCM key derivation input; this must be replaced, not merely hidden behind Google account sign-in.
- The trial branch's tracked ZIP baselines are older (Android v337 and PC v328). The PC v340 Library package and Android v340 successful-build artifact must be brought into the trial branch before implementation/build validation; do not silently build the new work against the older ZIPs.
- The Windows Desktop OAuth client ID and Firebase Google provider are now confirmed, so the remaining account-auth task is implementation rather than asking the user to create another OAuth client.

## First trial change

The Windows build workflow no longer publishes a separate `DevMonitor-Sync-Relay` artifact. It continues to produce the Windows installer and portable Windows application. The built-in Windows LAN bridge remains part of the DevMonitor app.


## Security finding requiring implementation

The current bridge is encrypted but not yet securely paired under the requested account model. Android and Windows share a manually entered token; the Windows process automatically approves the first successful encrypted exchange, and the server listens on a LAN-wide HTTP prefix. Account sign-in alone would not fix this: the sync protocol must bind the verified Firebase UID to a device identity and complete a one-time authenticated handshake before any database payload is accepted. Until this is implemented, do not describe the existing bridge as trusted automatic account pairing.
