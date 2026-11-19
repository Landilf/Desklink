# Desklink

Desklink includes an experimental VNC desktop viewer and an Android SSH client for an interactive Linux shell. The desktop viewer supports wayvnc's VeNCrypt X509Plain security with an explicitly configured certificate fingerprint. The terminal uses SSH PTY shell channels; the app does not call Codex APIs.

The desktop viewer is experimental. It has been used from an Android phone with Hyprland and wayvnc 0.10.2; rendering, input and lifecycle behaviour still need broader testing. Do not expose an unauthenticated VNC listener to the internet.

## Experimental wayvnc setup on NixOS

Install `wayvnc` and OpenSSL in your system or user environment. In the logged-in Hyprland session, create a certificate and config with restrictive permissions:

```sh
mkdir -p ~/.config/wayvnc
chmod 700 ~/.config/wayvnc
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:secp384r1 -sha256 \
  -days 3650 -nodes -keyout ~/.config/wayvnc/tls_key.pem \
  -out ~/.config/wayvnc/tls_cert.pem -subj '/CN=desklink'
chmod 600 ~/.config/wayvnc/tls_key.pem
```

Create `~/.config/wayvnc/config` with the following values, replacing the username and password with your own:

```ini
enable_auth=true
username=your-user
password=your-password
private_key_file=/home/YOUR_USER/.config/wayvnc/tls_key.pem
certificate_file=/home/YOUR_USER/.config/wayvnc/tls_cert.pem
```

Replace `YOUR_USER` in both paths with your Linux username, then run `chmod 600 ~/.config/wayvnc/config`. Desklink starts wayvnc automatically via SSH; the VNC address in the profile must be an IPv4 address assigned to an interface on the laptop. Allow TCP port 5900 on that interface in the NixOS firewall if needed. On the laptop display the certificate fingerprint and compare it to the value entered in Desklink:

```sh
openssl x509 -in ~/.config/wayvnc/tls_cert.pem -noout -fingerprint -sha256
```

In the connection profile, open the VNC tab and save the desktop address, port, optional VNC username, and the 64 hexadecimal digits from the certificate fingerprint. If the VNC address or username is blank, the corresponding SSH value is used. The VNC password is entered at connection time and is not saved. Tap the desktop icon on the profile to start wayvnc over SSH in a uniquely named detached `desklink-vnc-*` tmux session, then connect. The SSH host key must already have been verified by connecting to the host via SSH first; the server must have `tmux` installed. The automatic launcher accepts an IPv4 bind address. The client checks the VNC certificate before sending its password.

When the desktop screen is closed, Desklink reconnects over SSH and stops only the uniquely named tmux session it created. It also starts wayvnc with `--exit-on-disconnect`, so the server exits when the last established VNC client disconnects. If the phone is killed or loses network access, cleanup may fail and the server can remain running; use `tmux -S "$XDG_RUNTIME_DIR/desklink-tmux.sock" list-sessions` and `tmux -S "$XDG_RUNTIME_DIR/desklink-tmux.sock" kill-session -t <session-name>` to stop it manually. Do not run a separate wayvnc instance on the same address and port at the same time. Automatic startup and first-frame loading have been exercised on an Android device; repeated start/stop and abrupt termination should still be verified after changes. The viewer currently supports raw framebuffer updates, tapping and basic text input; dragging, robust gesture handling and server framebuffer resizing are not yet implemented.

## Technologies and structure

- Kotlin, Jetpack Compose and Material 3, with a single-activity MVVM screen.
- SSHJ 0.39.0 (Apache-2.0): SSH password/public-key authentication, PTY allocation, shell streams, resize and host-key verification. This Java 8-compatible release is selected for Android compatibility; SSHJ 0.41 requires Java 17.
- Termux `terminal-emulator` 0.118.0 (Apache-2.0 terminal-engine exception): VT/ANSI parsing, UTF-8, color/alternate-screen handling and transcript buffer. `TerminalCanvasView` renders the engine buffer and is hosted from Compose with `AndroidView`.
- DataStore Preferences stores connection profiles, VNC certificate fingerprints and trusted SSH host fingerprints. SSH passwords, private keys and key passphrases are AES-GCM encrypted with a non-exportable Android Keystore key. VNC passwords are entered for each connection and are not saved.
- The Compose screen and `TerminalViewModel` live in `presentation`; the terminal canvas is embedded with `AndroidView` for terminal drawing and Android keyboard input. `domain` contains the connection profile and repository contract. `data` contains the DataStore/Keystore implementation and SSH session manager. `di/AppContainer` wires these pieces together without a dependency injection framework. The terminal state currently connects the SSH manager to the presentation layer.

The Termux Android app itself is GPL-3.0-only, while its terminal emulator component is explicitly excepted as Apache-2.0; this app depends only on the terminal emulator artifact, not the GPL app or terminal-view modules. SSHJ is Apache-2.0.

## Build

Requires JDK 17, Android SDK Platform 37, Android Build Tools installed by Gradle, and network access to Maven Central/JitPack on the first build.

```bash
./gradlew :app:assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on an Android 8.0+ device. Internet permission is required. Backup is disabled because encrypted secret blobs depend on the device Keystore.

## Configure SSH and tmux on NixOS

Enable both in your NixOS configuration and rebuild using the command appropriate for your setup:

```nix
services.openssh.enable = true;
programs.tmux.enable = true;
```

Configure SSH authentication and firewall access for your network separately. The SSH user needs access to the same Wayland session and tmux environment as the desktop user. `wayvnc` must also be installed and available on that user's `PATH`.

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
3. Tap the terminal to open the Android keyboard; use the special-key rows for Esc, Tab, modifiers, arrows, Home/End, paging and Enter. Ctrl+C/D/L/Z shortcuts are provided.
4. To use Codex, enter commands in the shell, for example `cd ~/project` and then `codex`. The multiline editor offers plain insertion or bracketed paste, with a separate explicit “insert and Enter” action.
5. Enable “connect to tmux” in a profile to run `tmux new-session -A -s <name>`. SSH disconnect closes the client channel; it does not send a command to terminate tmux.

To use the same running Codex process on the laptop and phone, start it inside tmux on the laptop:

```sh
tmux new-session -A -s codex
cd ~/project
codex
```

Set the profile's tmux session name to `codex` and enable automatic attachment. The phone will attach to that existing session. To leave tmux running on the laptop, press `Ctrl+B`, then `D`; reconnect with `tmux attach -t codex`. Both devices may also attach simultaneously; tmux controls their shared terminal size. A Codex process started outside tmux cannot be attached to this tmux session using the application's existing integration. Resume its conversation after starting Codex inside tmux if necessary.

When tmux is enabled in the profile, the terminal's special keys include **Сессии tmux** and **Окна tmux**. They open tmux's built-in session and window picker; use arrows and Enter to select. These shortcuts use tmux's default `Ctrl+B` prefix and default `s` and `w` bindings. If those bindings have been customized, use the corresponding configured shortcuts instead. On connection the configured session is still attached or created first.

## Verification status

- Kotlin compilation and `:app:testDebugUnitTest` passed during development. Unit tests cover raw framebuffer decoding and RFB handshake. An opt-in local integration test connected to wayvnc 0.10.2 using VeNCrypt X509Plain, received a frame, and rejected a wrong certificate fingerprint.
- The user confirmed SSH connection, VNC display and remote input from an Android phone. Launch and cleanup through tmux were exercised; an orphaned server was observed during repeated connections, and cleanup was subsequently revised. The latest lifecycle changes still need confirmation on the phone.
- Still to verify: SSH key authentication and changed-host-key warning; full keyboard and gesture behaviour; Cyrillic and special keys through VNC; Codex TUI and tmux menus; resizing, reconnects, prolonged operation and network interruption. A 16 KB Android device has not been tested.

## Known limitations

- The VNC viewer currently implements only RFB 3.8, VeNCrypt X509Plain and raw framebuffer updates. It does not support framebuffer resizing, remote drag-and-drop, clipboard synchronisation or automatic certificate discovery. Full-frame bitmap allocation and raw updates can be slow over mobile networks.
- The SSH terminal emulator includes a `libtermux.so` native library that has been reported as not aligned for 16 KB page-size devices.
- Current UI provides one live terminal session. Multiple independent session tabs are not implemented yet.
- Profiles can be added, edited and deleted (with confirmation); import from Android document picker, SFTP, clipboard selection/copy, font controls and connection retry button are not implemented yet.
- The terminal canvas renders the VT engine's screen buffer and 256-color palette, but it is an initial renderer; test cursor shape/blinking, truecolor, wide glyphs, selection and touch scrolling on target devices before relying on it for every TUI.
- Keep the app in the foreground during interactive work. Android may suspend or kill background networking; tmux is recommended for processes that must survive phone disconnects.
- `terminal-emulator` is consumed from JitPack and the pinned artifact release is 0.118.0. Upgrading it should include a fresh API and license review.
