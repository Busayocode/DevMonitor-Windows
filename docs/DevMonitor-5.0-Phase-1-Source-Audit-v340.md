# DevMonitor 5.0 — Phase 1 Source Audit (v340 Source Snapshot)

**Audited artifact:** Android source ZIP captured by successful GitHub Actions run #173  
**Run:** 43373118893  
**Branch used for build:** trial/direct-google-account-lan-sync  
**Source ZIP:** DevMonitor-An-v340-4.7.0-TARGET-SMS-CHECKER-DAILY-HISTORY-ORIGINAL-CARD-LEGIBILITY.zip  
**Audit branch:** devmonitor-5.0-foundation  
**Scope:** SMS request construction, send callbacks, inbound receiver, parser, inbox recovery and daily history.  
**Important:** The audited source ZIP is v340. The run's release APK was v349 after workflow overlays. This audit must not be described as a complete source audit of every v349 overlay.

## 1. Confirmed source architecture

The current Android source has these distinct SMS components:

- `MainActivity.kt`: constructs and sends 3030 SMS requests, coordinates the SMS Checker run, monitors local captures and performs optional inbox recovery.
- `SmsReceiver.kt`: receives Android `SMS_RECEIVED` broadcasts and stores inbound message bodies in DevMonitor's local SMS capture store.
- `SmsParser.kt`: parses the captured message body into SIM registration result fields.
- `SmsDeliveryTracker.kt`: tracks carrier sent/delivered callbacks for outbound messages.
- `SmsCheckerDailyHistoryStore.kt`: persists per-day results separately from the transient live SMS capture path.

The app sends to short code `3030`. The source also documents that 3030 accepts a short device identifier form rather than the full hierarchical ID.

## 2. Request construction and sending

The source has a single default SMS destination constant set to `3030`. The send path uses Android `SmsManager`, checks `SEND_SMS`, and supports subscription-specific sending when a subscription ID is available, otherwise falling back to the default SMS manager.

The SMS send path has sent/delivered callback tracking. This is useful for gateway status, but a carrier "sent" or "delivered" callback is not proof that 3030 returned a valid result.

The source has a dedicated request-normalization function. It notes that the full DevMonitor device identifier is hierarchical and 3030 accepts only the short form. This exact request builder must be reused or extracted into a shared protocol module so Full and Gateway editions do not drift.

## 3. Inbound broadcast and capture

`SmsReceiver` listens for `android.provider.Telephony.SMS_RECEIVED`, reconstructs multipart message bodies by joining message segments, takes the originating address from the first segment, and immediately stores the message in the local DevMonitor SMS capture table.

This confirms that the normal foreground/live receive path is broadcast-based. It does not require reading the Android inbox for every response.

The receiver currently captures inbound SMS generally; it does not itself prove that a message belongs to a particular active query. That matching is performed later in the MainActivity processing path.

## 4. Parser and correlation findings

`SmsParser.extractCode(raw)` attempts to identify a device/agent code from the response using:
- A pattern for a GLO-GD, GLO-DD or GLO-FD code.
- A labelled Agent Code / Code / Device Code line.

It returns null when neither pattern matches.

The parser recognizes a successful response by the presence of both `Todays SIMReg Count` and `MTD SIMReg Count`; it also extracts fields including Today, MTD, Active, Bar 48, Bar 96, Duplicates and Monthly Target. It separately recognizes an "agent code does not exist" response.

**Important limitation:** The source parser's code extraction pattern is not proof that every real 3030 response contains a globally unique full device identifier. Some responses may omit a code or only contain a short suffix. The actual SMS response samples and MainActivity's matching fallback must be examined before allowing parallel gateway queries.

**Preliminary scheduling decision:** The gateway prototype must start with one in-flight query per gateway phone. Parallel queries on the same phone are prohibited until real response samples and matching logic demonstrate unambiguous device correlation. Multiple gateway phones may process independent queues in parallel, but each phone must remain serialized at first.

## 5. READ_SMS is recovery, not the primary receive mechanism

The source uses `READ_SMS` only for the optional system inbox reconciliation path. `SmsInbox.systemInbox()` checks permission and queries the Android SMS Inbox provider.

The first-class inbound response path is the SMS_RECEIVED broadcast and DevMonitor's own local capture store. This supports the previous assessment that READ_SMS can potentially be removed from a future edition after a carefully designed recovery replacement and regression tests. It does not establish that removal is already safe or implemented.

The Full edition remains unchanged.

## 6. Gateway implications

The existing SMS logic is embedded substantially in `MainActivity.kt`, rather than exposed as a clean transport interface. A safe Gateway Edition should not duplicate or rewrite the parser and daily history logic.

Recommended extraction boundary:

- Shared request builder and device-ID normalization.
- Shared response parser and normalized result model.
- Shared result validation and duplicate fingerprinting.
- Transport interface:
  - Direct SMS transport for Full edition.
  - HTTPS gateway transport for Gateway edition.
- Existing client-side history store remains the owner of local per-day history.

Gateway phone app should reuse the exact query builder and parser behavior. It should send normalized results to the controller, while Gateway Edition client persists the results through the established daily history store.

## 7. Confirmed unknowns requiring next inspection

The current static audit does not yet establish:

1. Whether every 3030 response includes a unique device identifier.
2. Whether the existing live run matches a response by extracted device code, request order, sender/time, or another fallback.
3. Whether two phones can safely query the same 3030 service simultaneously without carrier or service-side throttling.
4. Whether the current source ZIP contains all workflow overlays that produced the v349 APK.
5. How the current retry queue behaves under a process kill between SMS send and reply.

These are explicit Phase 1 follow-up checks. Do not implement same-phone parallelism or automatic reassignment of an uncertain send until they are answered.

## 8. Safety and preservation

- No source code was modified during this audit.
- No Full edition behavior was changed.
- No production branch was changed.
- No SMS permission was removed.
- No new SMS requests were sent.
- This is a source-level audit of the v340 input ZIP, not a live-network test against 3030.

## 9. Next audit actions

1. Trace MainActivity's complete inbound matching and queue loop around the parser and local SMS capture store.
2. Inspect the exact request string and device normalization function.
3. Inspect retry and interruption recovery state.
4. Compare v340 source ZIP with workflow overlay scripts and v349 APK build inputs.
5. Then write a concrete shared transport boundary and gateway proof-of-concept plan.

**Preliminary conclusion:** DevMonitor already has a broadcast-based inbound SMS capture path and a reusable parser. A multi-phone gateway is technically plausible. Safe scaling depends first on proving response-to-device correlation and uncertain-send recovery. Start with one active transaction per gateway phone; use multiple phones for initial parallel throughput.
