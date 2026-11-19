<h1 align="center">Embeng-Q</h1>

<p align="center">
  <strong>Cloudflare WARP / MASQUE VPN Client for Android</strong>
</p>

<p align="center">
  <a href="https://github.com/Embeng123/Embeng-Q/releases">
    <img src="https://img.shields.io/github/v/release/Embeng123/Embeng-Q?style=flat-square" alt="Release">
  </a>
  <a href="https://github.com/Embeng123/Embeng-Q">
    <img src="https://img.shields.io/badge/platform-android-blue?style=flat-square" alt="Platform">
  </a>
  <a href="https://github.com/Embeng123/Embeng-Q/actions/workflows/android.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/Embeng123/Embeng-Q/android.yml?branch=main&style=flat-square" alt="Android APK">
  </a>
</p>


---

## ✨ Features

- 🚀 **Cloudflare WARP** - MASQUE-based WARP tunnel using the upstream Go core
- 🔒 **Global VPN** - Route all app traffic through the VPN
- 📱 **Per-App VPN** - Route only selected apps through the VPN
- ⚙️ **Connection settings** - Edit SNI and server address directly on Home; default SNI: `classroom.google.com`
- 📊 **Speed Display** - Traffic speed for the VPN interface; shows unavailable when Android does not expose its counters
- 🛡️ **Foreground Service** - Better long-running VPN stability on Android
- 🔁 **Auto Restart** - Automatically restarts the native tunnel after unexpected disconnects
- ⚡ **Native Core** - Go + gomobile AAR integration for native performance

## 📲 Download

Download the APK from the [Releases](https://github.com/Embeng123/Embeng-Q/releases) page.

APK asset names follow these patterns (depending on whether release signing is configured):

```text
embeng-q-release-v1.0.2.apk
embeng-q-debug-v1.0.2.apk
```

> The GitHub Actions workflow reads `versionName` from `app/build.gradle` and publishes the APK to the matching versioned Release, for example `1.0.2` → `v1.0.2`.

### App identity

- App label: `Embeng-Q`
- Application ID and namespace: `com.embeng.q`
- Kotlin sources: `app/src/main/kotlin/com/embeng/q/`
- Launcher activity: `com.embeng.q.MainActivity`
- VPN service: `com.embeng.q.EmbengVpnService`

The `usqueandroid` / `go` packages and `usque` library filenames belong to the bundled upstream VPN engine. Keep them unchanged: the native bindings depend on those names.

## 🛠️ Build from Source

### Prerequisites

- JDK 17
- Android SDK, API 34
- Gradle 8.5 (downloaded by the included Gradle wrapper)
- Prebuilt upstream Android artifacts already included in this repository:
  - `app/libs/usque.aar`
  - `app/libs/usque-classes.jar`

### Build Steps

```bash
# Debug APK
./gradlew clean :app:assembleDebug :app:lintDebug

# Release APK
./gradlew clean :app:assembleRelease :app:lintRelease
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

Local APKs are generated under `app/build/outputs/apk/`. A release APK is unsigned unless all four signing environment variables are set: `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. `ANDROID_KEYSTORE_FILE` can be an absolute path or a path relative to the repository root. Keep the keystore and passwords outside version control. To install an unsigned release, sign it yourself (for example with `apksigner`).

### GitHub Actions Recommended

Push to `main` or manually run the Android APK workflow:

```bash
gh workflow run android.yml
```

The workflow will:

- Build with JDK 17 and Gradle 8.5
- Run `./gradlew clean` and Android lint to avoid stale output and catch build issues
- Build a signed release APK when all four repository secrets are configured: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`
- Otherwise build an installable debug APK (debug signing is not suitable for a stable production release)
- Verify the built APK uses `com.embeng.q` and has a valid signature
- Upload the APK and `SHA256SUMS` to the matching GitHub Release

## 📖 Usage

1. **Install APK** - Download and install the latest APK on an Android device.
2. **First Run** - Open the app with an internet connection. It automatically registers a Cloudflare WARP account if no valid config exists. Home shows the account status and offers a retry if registration fails.
3. **Configure** - Edit SNI and the server address directly on Home. The SNI starts as `classroom.google.com`; type over it to use another domain.
4. **Choose Mode** - Use Global mode or Per-App mode.
5. **Connect** - Tap the connect button to start the VPN.

**Reset defaults** restores both the SNI (`classroom.google.com`) and the default server address. The Apps tab uses a compact searchable list with selection controls; only the list scrolls while search and actions stay visible.

To replace an account, disconnect the VPN and choose **Create new account** on Home. The existing account is only replaced after the new registration succeeds. This creates a new account; it does not renew an existing account or apply a WARP+ license.

Connection status follows the tunnel service: Connecting, Connected, Reconnecting, and Disconnected. Reopening the app keeps the current service status. If Android restarts the service, it restores the SNI, endpoint, routing mode, and selected apps from the last started session. Edits made during a connection apply to the next connection.

The speed display reads the VPN interface rather than total device traffic. On Android 12 and later it uses interface-specific `TrafficStats`; older versions try interface counters exposed by the system. If those counters are inaccessible, the app displays **VPN speed unavailable**.

### Proxy Modes

| Mode | Description |
|------|-------------|
| **Global** | All app traffic goes through the VPN. The app itself is excluded to avoid routing the control connection back into its own tunnel. |
| **Per-App** | Only selected apps use the VPN. Other apps connect directly. The app itself is skipped for the same control-connection reason. |

### Settings

| Parameter | Example / Default | Description |
|-----------|-------------------|-------------|
| SNI | `classroom.google.com` | Editable TLS SNI domain on Home |
| Endpoint | `162.159.198.2:443` | WARP / MASQUE endpoint |
| Mode | Global / Per-App | Traffic routing mode |

## 🏗️ Architecture

```text
Embeng-Q/
├── app/
│   ├── src/main/
│   │   ├── kotlin/com/embeng/q/        # Kotlin UI and VPN service
│   │   ├── res/                        # Android resources
│   │   └── AndroidManifest.xml
│   ├── libs/                           # Prebuilt upstream AAR / Java classes
│   │   ├── usque.aar
│   │   └── usque-classes.jar
│   └── build.gradle
└── .github/workflows/
    └── android.yml                     # CI APK build and Release upload
```

### Tech Stack

- **UI**: Kotlin + Android Views
- **VPN Service**: Android `VpnService` + real TUN fd
- **VPN Core**: Go + gomobile → AAR
- **Protocol**: Cloudflare MASQUE / WARP
- **Build**: Gradle + GitHub Actions

## 🎨 Design

- **Style**: Material-style Android UI
- **Theme**: Soft warm orange / light card layout
- **Modes**: Overview, Config, Apps
- **UX**: Mobile-first controls and searchable app list

## 📝 Notes

- The app uses `VpnService.Builder.establish()` to obtain a real Android TUN fd.
- The detached fd is passed to the upstream native layer through `startTunnelWithFd`.
- Endpoint and SNI are configured through the `usqueandroid` API before the tunnel starts.
- The VPN service runs as a foreground service for better long-running stability.
- Unexpected native tunnel disconnects trigger automatic restart unless the user manually stopped the VPN.
- GitHub Actions uses `./gradlew clean` before building to prevent stale dex / manifest mismatches after package-name changes.

## 📝 License

This project is based on [`usque`](https://github.com/Diniboy1123/usque) by Diniboy1123. Check the upstream project license and comply with its terms when redistributing the combined work.

## 🙏 Acknowledgements

- [`usque`](https://github.com/Diniboy1123/usque) - Original WARP / MASQUE implementation
- [Cloudflare WARP](https://developers.cloudflare.com/warp-client/) - WARP platform
- [MASQUE](https://datatracker.ietf.org/doc/rfc9484/) - RFC 9484
