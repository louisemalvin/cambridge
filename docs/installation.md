# Installation and use

CamBridge currently requires an Android phone and a Linux x86_64/amd64
computer running OBS Studio. Put both devices on the same trusted local
network.

The shared receiver also contains a macOS 12+ implementation and CI/package
path, but macOS is not a supported downloadable receiver yet. Its arm64 and
x86_64 VideoToolbox/Metal/Bonjour acceptance and clean-machine package gates
must pass before the public installation path changes.

The repository also contains an iOS 17.4 sender install candidate. It is not a
released or supported platform yet: Apple-target compilation, signing, a real
iPhone camera/encoder, and glass-to-glass behavior must be validated on macOS
before the public support statement changes.

Install CamBridge components from the same `CamBridge X.Y.Z` release. The
Android APK is a public release asset. Linux installation is currently a
source build against the computer's installed OBS and FFmpeg packages. Both
ends must use the same CamBridge release generation; protocol v7 is not
compatible with protocol v6 from the 0.2.x releases.

## Android

1. Open the
   [CamBridge releases](https://github.com/louisemalvin/cambridge/releases) and
   download `cambridge-android-<version>.apk` from the matching `CamBridge
   <version>` release.
2. Install the APK. Android may ask you to allow installs from the browser or
   file manager used to open it.
3. Open CamBridge and allow camera access when prompted. The app also needs
   network access to discover and stream to the OBS computer.

CamBridge shows a foreground notification while streaming. The notification
includes the Stop control; removing the app task also stops the active stream.

## Linux / OBS

1. Install the OBS development package, FFmpeg development libraries, CMake,
   a C++17 compiler, GStreamer 1.24.13 or newer with the Rust RTP plugins
   `rtpgccbwe` and `rtprtxreceive`, and the native libraries listed in the
   [development prerequisites](development.md#prerequisites).
2. Download the source archive for the same `CamBridge X.Y.Z` release, or clone
   the repository and check out its `vX.Y.Z` tag.
3. Build and stage the plugin:

   ```bash
   ./scripts/receiver/linux/build-cambridge-obs-plugin.sh
   ```

4. Install the staged module into the current user's OBS plugin directory:

   ```bash
   plugin_dir="${XDG_CONFIG_HOME:-$HOME/.config}/obs-studio/plugins/cambridge-obs-plugin/bin/64bit"
   mkdir -p "${plugin_dir}"
   cp build/cambridge-obs-plugin/staging/obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so \
      "${plugin_dir}/"
   ```

5. Restart OBS, add a source named **CamBridge**, and keep the default source
   settings for normal use.

## macOS status

There is no supported macOS package to install from the current public release.
Maintainers may use the development build and release-package workflow on
validated macOS hosts, but should not treat a software-rendered fixture or a
successful compile as macOS support. When the physical gates pass, the package
will install one `cambridge-obs-plugin.plugin` bundle under
`/Library/Application Support/obs-studio/plugins` and will use the same source
IDs and manual-address fallback as Linux.

## Start a stream

1. Make sure OBS is running with the CamBridge source present.
2. Open CamBridge on the phone. The app opens to **Settings**.
3. Open **Streaming setup**, then wait for the receiver check to find the OBS computer.
   If more than one receiver is available, choose the intended computer from
   the selector; CamBridge never selects an arbitrary discovery result.
   If discovery cannot find it, enter the OBS computer's IP address or host in
   the manual address field and press **Use address**.
4. Choose a phone-supported resolution, frame rate, bitrate, and landscape or
   portrait orientation. The phone owns these video choices; OBS does not
   replace or downgrade them.
5. Press **Start stream**. Press **Stop stream** in the app when finished.

## Use the phone as a webcam

CamBridge makes the phone available as an OBS source. To use that source in a
video-call application:

1. Frame the CamBridge source in an OBS scene.
2. Start **Virtual Camera** from the OBS Controls dock.
3. Select **OBS Virtual Camera** as the camera in the other application.

On Linux, OBS Virtual Camera uses `v4l2loopback`. Follow the official
[OBS Virtual Camera guide](https://obsproject.com/kb/virtual-camera-guide) and
[troubleshooting guide](https://obsproject.com/kb/virtual-camera-troubleshooting)
for installation and platform-specific requirements.

## Network and firewall

Receiver discovery uses the local network. If the phone cannot find OBS, check
that both devices are on the same network, OBS is running, and the Linux
firewall permits CamBridge's control TCP port and media UDP port from the
phone. The default ports are listed in [Protocol](protocol.md), and the OBS
source settings are authoritative if they have been changed.

CamBridge keeps discovery active while Stream Setup is open and tries every
framework-resolved IPv4 address plus the bounded IPv4 unicast candidates advertised by
the OBS service. This allows a multi-homed receiver to advertise LAN and VPN
addresses without any VPN-specific address in the app. The advertised DNS-SD
port is used directly; CamBridge does not scan ports.

If macOS or OBS prompts for local-network access, allow it for Bonjour discovery.
If that permission is denied, discovery reports a degraded status and manual
receiver addressing remains available; it does not change the selected media
path.

CamBridge is not encrypted or authenticated. Do not expose these ports to the
internet; see [Known limitations](known-limitations.md).

## iOS development install candidate

On a Mac with Xcode 16.4 or newer, open `sender/ios/CamBridge.xcodeproj`
directly. Select the local Personal Team or paid team in Xcode for a device
run; no team, certificate, profile, or account information is committed.
Choose an iPhone destination, grant camera and local-network permission, and
run the checks and concise physical validation in the
[iOS sender README](../sender/ios/README.md). The unsigned simulator check cannot validate the
camera, hardware VideoToolbox, or local-network behavior.
