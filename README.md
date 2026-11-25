# Relay

A modern, private SMS & MMS app for Android — built with Kotlin, Jetpack Compose and Material 3.
No ads, no trackers, no analytics, no Google services. Internet is used only for GIF search (Klipy) and link previews. Desktop linking runs over your local network only — no cloud, no account. The SMS Tech engine is Apache-2.0; Relay's own code and brand are reserved — see License & Copyright.

> **Fork note:** Relay is based on [SMS Tech](https://github.com/gitubpatrice/SMS-TECH) by Patrice Haltaya (Files Tech). It keeps the SMS Tech engine; Relay's UI and new features are © 2026 Relay Messaging.

Source: [github.com/relay-sms/relay-android](https://github.com/relay-sms/relay-android) ·
Releases: [GitHub Releases](https://github.com/relay-sms/relay-android/releases/latest)

## ✨ Features

- Default SMS / MMS app for Android 8.0 and later (tested up to Android 16), with a one-time import
  of your existing messages.
- Single-Activity Compose UI, Material 3 with dynamic colors, AMOLED true-black and
  System / Light / Dark themes.
- **End-to-end encryption between Relay users**: texts, reactions, photos, videos, voice messages
  and files in 1:1 chats, and texts, reactions, photos, videos, voice messages and files in groups where
  every member uses Relay — automatic, with no server, no internet and no account (vodozemac). Encrypted conversations carry a lock, and safety
  numbers let you verify a contact.
- **Link your desktop, tablet, or a second phone** over your local network: read and send from any
  linked device, with calls through the phone. The phone stays the messaging device; pairing is a
  QR scan and the device link is end-to-end encrypted.
- **Client mode**: a second phone can link to your main phone as a client instead of acting as its
  own messaging device.
- Encrypted Room database (SQLCipher), master key wrapped by the AndroidKeyStore.
- App lock: PIN with PBKDF2-HMAC-SHA512 and exponential backoff after failed attempts; optional
  biometric unlock with a mandatory fallback PIN.
- **Vault** for sensitive conversations: hidden from the main list and opened with its own PIN,
  passphrase or biometrics. It lives in the same SQLCipher-encrypted database, not in a separately
  keyed envelope — see [SECURITY.md](SECURITY.md) for the threat model and its limits.
- Optional **decoy PIN**: entering it opens the app in a session where the vault, emergency mode and
  Safety call are invisible and unreachable.
- **Notes**: private local-only threads for notes to yourself — text, photos, files and voice —
  synced to your linked devices, never sent as SMS.
- **Emergency mode**: hold a button for three seconds to text your chosen contacts, with your location
  if you allow it (precise, or approximate and marked as such), and call tiles for the emergency
  numbers of the country your phone is registered in. **Safety call** texts the same contacts if you
  do not open the app within a delay you set. These features help; they do not replace emergency
  services — see the [terms of use](TERMS.md).
- **Voice messages** recorded on the device and sent as audio MMS.
- Named groups and group MMS, contextual replies with quotes, inline reply from notifications.
- **Pinned messages**: pin a message in a thread and jump back to it from the banner.
- **Emoji reactions** on message bubbles, sent as tapbacks.
- **Smart actions**: one-tap actions on what a message contains — OTP / login codes (copy), phone
  numbers (call), email addresses (send email), physical addresses (open in your maps app, with a
  tap-to-load map preview), dates and times (add to calendar), and tracking numbers (track the
  package — UPS, USPS, FedEx). In the bubble and in the notification; detection is on-device.
- Offline **scam detection** (shortened links, urgency wording, premium numbers, spoofed domains), on
  rules: it can be wrong both ways.
- Blocking synchronized with the Phone / Samsung Messages blocklist; optional blocking of unknown
  numbers.
- Full-text search (FTS4) across conversations and **inside a thread**, **PDF export** of a conversation, scheduled sending (WorkManager).
- Manual backup &amp; restore in `.smsbk` (AES-256-GCM + PBKDF2). Attachments are not part of the
  backup.
- **Invite friends**: share Relay from the conversation list or Settings.
- Five languages: English, French, German, Italian and Spanish — including every SMS the app sends
  on your behalf. Pick one per app on Android 13 and later, or follow the system.
- F-Droid friendly: no Google libraries, no proprietary blobs, reproducible build.

## 🔐 Privacy

We collect **nothing**. No analytics, no crash reporting, no remote logging.

**End-to-end encryption.** Messages between people who both use Relay are end-to-end encrypted with
[vodozemac](https://github.com/matrix-org/vodozemac), the open-source encryption library from Matrix,
independently audited by Least Authority in 2022. The encryption runs over SMS and MMS directly —
there is no Relay server, no internet connection, and no account involved. Your encryption keys are
created on your phone and never leave it.

In a 1:1 conversation, texts, reactions, photos, videos, voice messages and files are all encrypted
once the session is established. In a group, texts, reactions, photos, videos, voice messages and files are
encrypted when every member uses Relay. A text caption typed on an encrypted photo or video stays
plain — only the media itself is encrypted. A lock marks encrypted conversations and messages. The first
message to a new contact may go out plain while the key exchange runs in the background; from then
on, everything encrypts automatically. Messages sent before the other person had Relay stay plain —
they can't be retroactively encrypted.

Verification is optional: each encrypted contact has a safety number you can compare in person or
over another channel, and mark them verified. Messaging works encrypted whether or not you verify.

Messages to people without Relay are sent as plain SMS/MMS over the carrier — that's a limit of SMS
itself, not something Relay can change. And if the other person stops using Relay, the conversation
goes back to plain, with a notice in the thread.

**Linked devices.** The link between your phone and your desktop, tablet, or second phone is
end-to-end encrypted too. Your encryption keys stay on your phone; linked devices receive decrypted
content over the encrypted link.

**On this device.** Messages are stored in an encrypted database (SQLCipher), with the master key
wrapped by the AndroidKeyStore.

Relay holds the `INTERNET` permission for two features only: GIF search via Klipy — the words you
type are sent to Klipy (`api.klipy.com`) and the GIF you tap is downloaded from there — and link
previews. Nothing else in the app uses the network, and the app still works if the permission is
blocked (GIF search just returns nothing). MMS are carried by Android's own MMS service, which talks
to your carrier's MMSC.

See the [privacy policy](PRIVACY.md), the [terms of use](TERMS.md) and
[PERMISSIONS.md](PERMISSIONS.md). Security model: [SECURITY.md](SECURITY.md)
([français](SECURITY.fr.md)). The privacy policy and the terms exist in English, French, German,
Italian and Spanish; English and French are authoritative.

## 📦 Build

```bash
./gradlew assembleDebug          # → app/build/outputs/apk/debug/*.apk
./gradlew test detekt ktlintCheck lintDebug
```

Requires JDK 17. Min SDK 26 (Android 8.0), compile SDK 37, target SDK 35.

## 🏗️ Architecture

```
core/      Result, AppError, crypto (AES-GCM + Keystore + PBKDF2), Timber wrapper
data/      Room (entities, DAOs, FTS4), DataStore, ContentResolver wrappers, repositories
domain/    Immutable models, repository interfaces, UseCases
system/    Receivers (SMS_DELIVER, WAP_PUSH, sent/delivered, boot), Services
           (HeadlessSmsSendService), Notifications (channels, MessagingStyle, inline reply),
           Schedulers (WorkManager)
security/  AppLockManager, AutoLockObserver (ProcessLifecycleOwner), VaultManager, PanicService
e2e/       End-to-end encryption (vodozemac sessions, handshake, fragments, safety numbers)
sync/      Device linking (LAN server, QR pairing, client mode, encrypted device link)
ui/        Theme (M3, dynamic, AMOLED), Navigation (type-safe), Screens (Compose), ViewModels
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full picture and the end-to-end flow of a received SMS.

## License & Copyright

### Original Project

This project is a modified and extended derivative of **SMS Tech**, originally developed by Patrice
Haltaya (Files Tech). The original SMS Tech application, including its underlying source code and
original contributions, remains attributable to its respective copyright holder and is distributed
under the Apache License, Version 2.0. All applicable original copyright notices, attribution
requirements, and license obligations are retained — see [NOTICE](NOTICE) and [LICENSE](LICENSE).

### Relay Original Contributions

Since forking SMS Tech, this project has undergone substantial independent development, including a
redesigned user interface, revised user experience, end-to-end encryption, device linking, and new
features.

Copyright © 2026 Relay Messaging. All rights reserved for original contributions made to this
project, including, where independently authored:

- Original UI and UX implementations.
- Original visual design systems, layouts, and styling.
- Original graphics, icons, and artwork.
- Device linking and synchronization code.
- End-to-end encryption code.
- Independently developed features and functionality.
- Original documentation and other creative works.

Files carrying the Relay proprietary header are the exclusive property of Relay Messaging and are
not licensed under the Apache License 2.0 — see [LICENSE-RELAY](LICENSE-RELAY) for the terms.
Files modified from the original carry a modification notice: the original SMS Tech code in
them remains under the Apache License, Version 2.0, while Relay's modifications to those files
are proprietary to Relay Messaging — see [LICENSE-RELAY](LICENSE-RELAY) for the terms. The
underlying SMS Tech code and all other third-party components remain subject to their
respective licenses.

### Trademark

"Relay", the Relay logo, and all Relay brand assets are trademarks of Relay Messaging and are not
licensed under the Apache License 2.0 or any other open-source license. Forks and derivatives must
not use the Relay name, logo, or branding.

See [LICENSE](LICENSE). Third-party libraries and their licences:
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
© 2026 Relay Messaging. Based on SMS Tech by Patrice Haltaya.
