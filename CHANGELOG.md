# HiddenCam 1.1.0 Changelog

## Security & Session Model
- Persistent unlocked sessions: the application and protected folders remain unlocked during normal navigation. Locking occurs only via the explicit Lock button or the emergency Double Volume-Up action.
- Centralized `SecurityCleanup` used by both manual lock and emergency lock.
- Optional Duress Code: a separate code (stored only as a salted verifier) that, when entered on the unlock screen, immediately performs a complete wipe of all HiddenCam-controlled private data with no confirmation dialog.
- Session keys held exclusively in memory (`SessionManager`).

## Authentication
- Calculator-style PIN keypad with explicit "=" submit button.
- PIN length handling relaxed to support Duress Code configuration (4–12 digits).
- Normal PIN remains the primary unlock credential.

## Encryption & Recordings
- Protected folders continue to use AES-256-GCM with strengthened PBKDF2 parameters (600 000 iterations).
- New recordings are directed to the currently active unlocked protected folder when one is available.
- Encryption success is verified before plaintext temporary files are deleted; recovery path preserved on failure.
- Keys never written to persistent storage.

## UI & Text Experience
- Short, neutral responsible-use warning replaces the previous longer legal/ethics text.
- Improved Lock button and Security settings entry for Duress Code management.
- Double Volume-Up emergency lock retained and integrated with the new cleanup path.

## Removed / Explicitly Not Present
- No Device Administrator functionality.
- No automatic lock-on-navigation or arbitrary lock timers.
- No Grok / AI branding, dependencies, or integrations.

## Technical notes
- Argon2id was evaluated; the implemented design uses high-iteration PBKDF2-HMAC-SHA256 for folder passwords (practical, pure-Java, no extra native dependency). The architecture is otherwise comparable in goals to strong password-based AES-GCM containers.
- Ordinary Android applications cannot intercept volume keys while fully backgrounded or with the screen off; the emergency trigger works when the process receives the key events.
- Cryptographic erasure is used; physical multi-pass flash overwrite is not claimed.
