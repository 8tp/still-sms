# still-sms — spec

A monochrome, network-free SMS/MMS app for GrapheneOS. Replaces AOSP Messaging
in the system role. Differentiates by removal, not by addition.

## the pact (inherited from the still family)

- **No `INTERNET` permission.** Manifest-enforced. Cannot phone home.
- **No Firebase, GMS, analytics, AI SDKs.** AndroidX + Compose + DataStore only.
- **Plaintext export forever.** Threads round-trip to `.csv` and `.txt`.
- **Monochrome, OLED-true black.** `#000` background, `#ECEBE7` foreground, one
  `#232320` hairline. No accent color. No ripple — 60 ms opacity fade only.
- **Lowercase verbs.** "send", "search", "archive", "block", "call".
- **No per-conversation theming.** Quik's signature feature; we cut it.
- **One screen per concept.** Settings is a single scroll, max ~8 toggles.

## what we explicitly do not ship

These are anti-features for a reason. Document each in the README.

- **No avatars or contact photos.** Initials in a 1-bit circle, or nothing.
- **No reactions, stickers, GIF picker, emoji panel.** System keyboard handles
  emoji.
- **No read receipts, delivery receipts, typing indicators.** SMS doesn't have
  them natively. Don't fake them. Failures are the only signal worth showing.
- **No RCS.** Google's Jibe APIs are not public to third parties. Document in
  About; recommend Signal/Molly for E2EE.
- **No group MMS by default.** Single biggest bug surface in every FOSS SMS
  app. Behind an explicit settings toggle, off by default. 1:1 MMS first.
- **No scheduled send.** Quik has it; AOSP doesn't. Lean Still = no.
- **No backup/restore to cloud.** Local export only.
- **No per-thread mute, per-thread notification sound, per-thread color.** Mute
  is global; sound is global; color is monochrome.

## screens

Three screens. That's it.

### `ThreadListScreen`
- Top: `search` verb opens an inline filter (regex against sender + body).
- Body: vertical list of conversations sorted by most-recent-message.
  - Sender display name (or raw number if no contact match).
  - Last-message preview (one line, ellipsized).
  - Timestamp in mono caption.
  - Unread state = bold display name, no badge, no dot.
- Footer: `compose` verb (bordered, per pact). Long-press a row →
  `archive` / `block` / `delete` overflow.

### `ThreadScreen`
- Title: contact display name or raw number, tappable to open the contact in
  still-contacts via `ACTION_VIEW`.
- Body: messages stacked top-to-bottom, oldest-first. Inbound left-aligned in
  `MutedWhite`, outbound right-aligned in `SoftWhite`. No bubble fill; a
  hairline rule separates each message. Timestamp under each message in
  caption.
- Footer: composer — single-line text field, `send` verb to the right.
  Attachment paperclip opens system file picker (one image at a time, sent
  as MMS).
- Long-press a message → `copy` / `forward` / `delete`.

### `SettingsScreen`
Single scroll, eight rows max. Per pact.
- font preset (cycle)
- 24-hour timestamps (toggle)
- haptic feedback (toggle)
- group mms (toggle, default off, with a paragraph explaining the bug surface)
- mms auto-download on mobile data (toggle, default on)
- default sim (cycle, on dual-sim devices only)
- blocked numbers (opens `BlockListScreen`)
- export threads (writes `still-sms-YYYY-MM-DD.zip` with per-thread `.txt` to
  `Documents/`)

### bonus: `BlockListScreen`
Lives under settings. List of blocked numbers, `add` verb opens a numeric
entry, `remove` is long-press. Numbers are stored in the local block table; a
`CallScreeningService`-style filter rejects matching SMS at `SMS_DELIVER` time.

## required manifest declarations

To be eligible for `RoleManager.ROLE_SMS`, all four must exist
([Android docs](https://developer.android.com/reference/android/app/role/RoleManager)):

```xml
<!-- 1. Activity for sms:/mms:/smsto:/mmsto: intent -->
<activity android:name=".ComposeActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.SENDTO" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="sms" />
        <data android:scheme="smsto" />
        <data android:scheme="mms" />
        <data android:scheme="mmsto" />
    </intent-filter>
</activity>

<!-- 2. SMS_DELIVER receiver -->
<receiver android:name=".sms.SmsDeliverReceiver"
    android:exported="true"
    android:permission="android.permission.BROADCAST_SMS">
    <intent-filter>
        <action android:name="android.provider.Telephony.SMS_DELIVER" />
    </intent-filter>
</receiver>

<!-- 3. WAP_PUSH_DELIVER receiver (MMS) -->
<receiver android:name=".mms.MmsDeliverReceiver"
    android:exported="true"
    android:permission="android.permission.BROADCAST_WAP_PUSH">
    <intent-filter>
        <action android:name="android.provider.Telephony.WAP_PUSH_DELIVER" />
        <data android:mimeType="application/vnd.wap.mms-message" />
    </intent-filter>
</receiver>

<!-- 4. RESPOND_VIA_MESSAGE service -->
<service android:name=".sms.RespondViaMessageService"
    android:exported="true"
    android:permission="android.permission.SEND_RESPOND_VIA_MESSAGE">
    <intent-filter>
        <action android:name="android.intent.action.RESPOND_VIA_MESSAGE" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:scheme="sms" />
        <data android:scheme="smsto" />
        <data android:scheme="mms" />
        <data android:scheme="mmsto" />
    </intent-filter>
</service>
```

## permissions

Seven runtime permissions, all disclosed honestly in README and About.

| permission | why |
|---|---|
| `SEND_SMS` | outgoing SMS via `SmsManager.sendTextMessage()` |
| `READ_SMS` | read the sms-mms provider to render threads |
| `RECEIVE_SMS` | catch inbound SMS before SMS_DELIVER (compat) |
| `RECEIVE_MMS` | catch inbound MMS WAP push |
| `RECEIVE_WAP_PUSH` | receive `WAP_PUSH_DELIVER` MMS notification PDUs |
| `READ_CONTACTS` | resolve numbers → display names |
| `POST_NOTIFICATIONS` | Android 13+, new-message notifications |

**Notably absent:** `INTERNET`, `READ_PHONE_STATE` (unless dual-SIM), `READ_EXTERNAL_STORAGE`
(use SAF / system file picker), any location, any biometrics.

## data layer

### sms storage
Don't reinvent the wheel. Read/write the system `mms-sms` content provider:
`content://sms` and `content://mms`. We are the default app, so writes
require no extra permission.

```
data/
  SmsRepository.kt        // ContentResolver queries against content://sms
  MmsRepository.kt        // ContentResolver against content://mms, with PDU
                          //   parsing for inbound, building for outbound
  ThreadRepository.kt     // Joins SMS + MMS into a single thread list with
                          //   pagination
  BlockListRepository.kt  // Local file: filesDir/blocked.json
  PreferencesRepository.kt // DataStore for the settings toggles
```

### blocked numbers
Stored as a plaintext JSON list of canonical exact-match keys:
`+15551234567` for strict E.164, `12345` for short/national numeric
senders, and `BANK-ID` for alphanumeric sender IDs.
`SmsDeliverReceiver` checks every inbound message against this list before
inserting into the provider; matches are dropped silently (no notification, no
provider write).

### threads list
Use `Telephony.Threads.CONTENT_URI` with column `_id`, `recipient_ids`,
`snippet`, `date`, `read`, `message_count`. Joined to a thumbnail via
`Telephony.MmsSms.CONTENT_CONVERSATIONS_URI` for cross-cutting queries.

### export
On demand, walk every thread, dump messages as a `.txt` file per thread named
`{number-or-name}.txt`, zip into `Documents/still-sms-YYYY-MM-DD.zip` via SAF.
Format:

```
2026-04-12 14:32  ->  hi
2026-04-12 14:33  <-  yo
```

`->` is outbound, `<-` is inbound. `cat`-able by design.

## mms strategy

**Primary path: `SmsManager.sendMultimediaMessage()` (API 21+).**

It handles MMSC URL lookup via the carrier's APN configuration, so we don't
need to hardcode T-Mobile / Verizon / AT&T MMSCs ourselves.
([android-smsmms README](https://github.com/klinker41/android-smsmms))

The send path:
1. User picks an image via system file picker (SAF).
2. We build a PDU using `MmsHelper.buildSendReq(...)` (port a small subset of
   the klinker41 logic; do not depend on the full library — most of it is
   pre-Lollipop code we don't need).
3. Write PDU to a temp file in `cacheDir`, get a content URI via
   `FileProvider`.
4. Call `SmsManager.sendMultimediaMessage(context, contentUri, locationUrl=null,
   configOverrides=null, sentIntent=PendingIntent)`.
5. The `sentIntent` BroadcastReceiver records success/failure into our
   provider write.

The receive path:
1. `MmsDeliverReceiver` fires with the WAP push PDU bytes in the intent extras.
2. Parse the M-Notification.ind to find the content location.
3. Use `SmsManager.downloadMultimediaMessage(context, locationUrl, contentUri,
   configOverrides=null, downloadedIntent=PendingIntent)` to fetch via the
   carrier's MMS APN.
4. Parse the resulting PDU, write parts to the system `mms` provider.

**If `sendMultimediaMessage` fails on a specific carrier** (Mint, Tello,
Boost):
- Fall back to the [ProminentRetail/android-smsmms](https://codeberg.org/ProminentRetail/android-smsmms)
  manual path (Android 10+ fixes layered over klinker41).
- Document the carrier-quirks list in `docs/mms-quirks.md` as we discover them.

**Beta carrier gauntlet:** Verizon US, T-Mo US, AT&T US, Mint, Tello,
GiffGaff UK, Vodafone DE. Don't ship 1.0 without 1:1 MMS working on at least
the first four.

## notifications

Single channel: `messages` (importance `HIGH`).

- Inbound message → notification with sender name, message preview,
  `reply` (RemoteInput) and `mark read` actions.
- RemoteInput reply path: writes the message via SmsManager, inserts into
  provider, dismisses notification. No activity launch.
- Tap → opens `ThreadScreen` for that thread.

Notification text never contains delivery status (no "read", no "delivered" —
per the pact).

## reusable components from the still family

Copy verbatim from still-clock / still-contacts:

- `ui/theme/StillColors.kt`
- `ui/theme/StillTypography.kt`
- `ui/theme/StillFontFamilies.kt` + font assets in `res/font/`
- `ui/components/StillDivider.kt` — 1dp hairline
- `ui/components/StillMenuItem.kt` — text-first rows
- `ui/components/StillVerb.kt` — lowercase verb with optional border
- `LocalHaptics` composition local
- `PreferencesRepository` pattern (Flow + suspend setters)
- Hand-rolled `sealed class Route { ... }` router

Integrate with **still-contacts** via `Intent(ACTION_PICK,
"vnd.android.cursor.dir/contact")` to start a new conversation — returns a
`lookupKey` we resolve to a phone number via ContentResolver. No shared
phone-number normalization lib; we lift Android's `PhoneNumberUtils`
`normalizeNumber()` for E.164.

## what we leave for future work

- **Backup/restore from local file:** `.zip` import to replay history into a
  clean device. Doable, deferred until 1.1.
- **Smart linkification:** detect URLs, phone numbers, addresses in message
  body. Show as monochrome underlined text, system handles tap.
- **Per-thread mute window:** "mute for 2 hours" type UX. Probably never — too
  much settings sprawl.
- **Lockscreen privacy:** redact preview on lockscreen unless device is
  unlocked. Useful but adds a settings toggle; reconsider for 1.1.

## release plan

- **0.1** — read-only thread list and thread view. No sending. Shipping path:
  default-SMS role works (system delivers to our receivers); we just render.
- **0.2** — outgoing SMS (1:1, text-only). Notifications + reply.
- **0.3** — outgoing 1:1 MMS (image). Inbound MMS. Carrier gauntlet.
- **0.4** — block list, export. Settings page.
- **0.5** — group MMS behind toggle. Search.
- **1.0** — gauntlet pass across the four major US carriers + two EU.

## references

- [FossifyOrg/Messages](https://github.com/FossifyOrg/Messages) — primary
  reference for SMS role wiring + provider queries (GPL-3.0, actively
  maintained).
- [quik-sms/quik](https://github.com/quik-sms/quik) — secondary reference for
  conversation-thread UX (strip the theming).
- [ProminentRetail/android-smsmms](https://codeberg.org/ProminentRetail/android-smsmms) —
  carrier-quirks fallback only.
- [Android default SMS role docs](https://developer.android.com/reference/android/app/role/RoleManager)
- [SmsManager.sendMultimediaMessage()](https://developer.android.com/reference/android/telephony/SmsManager#sendMultimediaMessage(android.content.Context,%20android.net.Uri,%20java.lang.String,%20android.os.Bundle,%20android.app.PendingIntent))

## license

MIT. Hard commitment. No relicensing. If ever unmaintained, archive — don't
sell. See the Simple Mobile Tools / ZipoApps case for why this matters.
