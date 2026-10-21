# Remote Terminal

Remote Terminal is an Android SSH client prototype for an interactive Linux shell. It uses SSH PTY shell channels, so programs such as `codex`, `vim`, `htop` and `tmux` run on the remote machine; the app does not call Codex APIs.

## Technologies and structure

- Kotlin, Jetpack Compose and Material 3, with a single-activity MVVM screen.
- SSHJ 0.39.0 (Apache-2.0): SSH password/public-key authentication, PTY allocation, shell streams, resize and host-key verification. This Java 8-compatible release is selected for Android compatibility; SSHJ 0.41 requires Java 17.
- Termux `terminal-emulator` 0.118.0 (Apache-2.0 terminal-engine exception): VT/ANSI parsing, UTF-8, color/alternate-screen handling and transcript buffer. `TerminalCanvasView` renders the engine buffer and is hosted from Compose with `AndroidView`.
- DataStore Preferences stores connection profiles and trusted host fingerprints. Passwords, private keys and key passphrases are AES-GCM encrypted with a non-exportable Android Keystore key.
- `TerminalViewModel` owns SSH resources and the background I/O coroutine. `ProfileStore` owns persistence. Each screen-level terminal model outlives Compose recompositions.

The Termux Android app itself is GPL-3.0-only, while its terminal emulator component is explicitly excepted as Apache-2.0; this app depends only on the terminal emulator artifact, not the GPL app or terminal-view modules. SSHJ is Apache-2.0.

## Build

Requires JDK 17, Android SDK Platform 37.1, Android Build Tools installed by Gradle, and network access to Maven Central/JitPack on the first build.

```bash
./gradlew :app:assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on an Android 8.0+ device. Internet permission is required. Backup is disabled because encrypted secret blobs depend on the device Keystore.

## Configure SSH on Ubuntu or Debian

On the Linux computer:

```bash
sudo apt update
sudo apt install openssh-server tmux
sudo systemctl enable --now ssh
sudo ufw allow OpenSSH   # if UFW is enabled
```

Use a reachable LAN address or a secure VPN address. Do not expose port 22 directly to the public internet without firewall restrictions and hardened SSH configuration. The app does not configure the remote server or install packages.

For key authentication, create or use an OpenSSH private key and add the corresponding public key to `~/.ssh/authorized_keys` for the selected remote account. Paste the private key into the profile editor; it is stored encrypted on the device. Password and key authentication can be configured per profile; if key text is present, key authentication is used.

## Connect and use Codex CLI

1. Add a profile with a recognizable name, host, port (usually 22), username and either password or private key.
2. Connect. On first connection, compare the displayed SHA-256 fingerprint with a trusted out-of-band source before accepting it. A changed key triggers a warning and explicit replacement confirmation.
3. Use the terminal input field for normal keyboard input and the special-key row for Esc, Tab, modifiers, arrows, Home/End, paging and Enter. Ctrl+C/D/L/Z shortcuts are provided.
4. To use Codex, enter commands in the shell, for example `cd ~/project` and then `codex`. The multiline editor offers plain insertion or bracketed paste, with a separate explicit “insert and Enter” action.
5. Enable “connect to tmux” in a profile to run `tmux new-session -A -s <name>`. SSH disconnect closes the client channel; it does not send a command to terminate tmux.

## Manual verification status

Build verification completed successfully with `:app:assembleDebug` and `:app:assembleDebugAndroidTest`. The instrumentation test is in `app/src/androidTest/.../TerminalEmulatorInstrumentedTest.kt`; it compiles, but could not be executed because `adb devices` found no attached Android device or emulator. No real SSH server or installed Codex CLI is available in this workspace, so the following remain manual checks:

- Password and key login against an SSH server, including encrypted OpenSSH keys.
- First-use host trust and changed-key warning behavior.
- Interactive shell stdin/stdout/stderr behavior, UTF-8/Cyrillic, ANSI colors, alternate screen, cursor and high-volume output.
- PTY resize after keyboard show/hide and orientation changes.
- Ctrl+C/D/L/Z, Tab, arrows, paste with and without bracketed paste, copy/selection and long-running TUI applications.
- Codex CLI prompts/permission menus and tmux detach/reconnect behavior.

## Known limitations

- Current UI provides one live terminal session. Multiple independent session tabs are not implemented yet.
- Profiles can be added, edited and deleted (with confirmation); import from Android document picker, SFTP, clipboard selection/copy, font controls and connection retry button are not implemented yet.
- The terminal canvas renders the VT engine's screen buffer and 256-color palette, but it is an initial renderer; test cursor shape/blinking, truecolor, wide glyphs, selection and touch scrolling on target devices before relying on it for every TUI.
- Keep the app in the foreground during interactive work. Android may suspend or kill background networking; tmux is recommended for processes that must survive phone disconnects.
- `terminal-emulator` is consumed from JitPack and the pinned artifact release is 0.118.0. Upgrading it should include a fresh API and license review.
