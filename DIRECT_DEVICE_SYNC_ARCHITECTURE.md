# Direct Android–Windows Device Sync — Trial Architecture

Status: design and source audit; implementation not yet certified.
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
- Windows has no equivalent Google/Firebase sign-in flow in this source package. Android's existing Google sign-in is exchanged for Firebase Authentication, but Windows does not yet authenticate the same Google identity.
- Android's near-real-time sync is currently scoped to an active foreground screen/session; this is not yet a fully background-resilient always-on sync service.
- Direct LAN discovery does not traverse routers or mobile networks. Different-network sync needs a managed cloud transport or a secure peer-to-peer connectivity mechanism. It must not be represented as direct LAN communication.

## Sync Relay decision

The standalone Sync Relay is not needed for same-network phone-to-PC exchange. It exists for remote synchronization when devices are on different networks.

The Windows installer already installs only the Windows app's `publish` directory; the relay is not embedded in that installer. The GitHub workflow previously emitted a third, separate `DevMonitor-Sync-Relay` artifact. This trial removes that artifact from the normal build outputs so users are not led to believe they must install it for local communication. The relay source is retained for now; no remote-sync functionality is being silently deleted.

## Target design

1. Add Google sign-in to Windows using a supported desktop OAuth flow and exchange the Google credential with the same Firebase Authentication project used by Android.
2. Expose the authenticated Firebase UID and per-installation device ID to the sync-pairing layer; never trust a client-supplied email string.
3. On the same LAN, Android discovers Windows automatically. The two authenticated apps compare verified account identity and complete a one-time device authorization handshake.
4. Derive or negotiate a unique device-pair key using a reviewed authenticated key-agreement protocol. Do not use the Google password, raw ID token, or a static user-wide secret as the AES key.
5. Persist the trusted device pairing securely and resume encrypted bidirectional LAN sync automatically when both apps are available.
6. Keep local data usable offline. Use retry/backoff, bounded batches, acknowledgements, conflict handling, deletion semantics, and recovery after sleep/reconnect.
7. Keep the current sync allow-list: do not transfer payment/MOM records, HQ snapshots, targets, or other protected business data without separate explicit approval.
8. Treat remote-network synchronization as a separate optional capability. If later enabled, it should use developer-managed Firebase/cloud infrastructure or another managed transport, never require the user to install a relay program.

## Required Google Cloud/Firebase setup

The Android source already has Google Sign-In and Firebase Authentication. The inspected Windows source does not. Windows needs an OAuth client configured for a desktop/installed application (with PKCE/loopback redirect) and a corresponding Firebase Authentication sign-in exchange. The existing Android/Web OAuth client IDs must not be assumed to be interchangeable with a desktop OAuth client. Do not place a client secret in the desktop app or repository.

Before the Windows sign-in implementation can be tested against the real project, confirm/create the desktop OAuth client ID in the existing Google Cloud project and verify that Google is enabled as a Firebase Authentication provider.

## Safety and regression boundaries

- Work only on this trial branch until the user tests and approves it.
- Do not merge to `main` without explicit approval.
- Do not modify payment/MOM formulas, cycle boundaries, target logic, HQ snapshots, or report calculations.
- Preserve AES-GCM confidentiality and validate peer identity before accepting sync payloads.
- Do not mark sync complete until LAN discovery, first pairing, two-way transfer, offline/reconnect, Windows sleep/resume, duplicate suppression, conflict handling, and deletion behavior are tested on real devices.
- Source inspection and a successful compile are not proof of runtime device-to-device behavior.

## First trial change

The Windows build workflow no longer publishes a separate `DevMonitor-Sync-Relay` artifact. It continues to produce the Windows installer and portable Windows application. The built-in Windows LAN bridge remains part of the DevMonitor app.
