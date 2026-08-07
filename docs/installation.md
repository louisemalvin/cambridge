# Installation and use

CamBridge currently requires an Android phone and a Linux x86_64/amd64
computer running OBS Studio. Put both devices on the same trusted local
network.

## Android

1. Open the [latest GitHub release](https://github.com/louisemalvin/cambridge/releases/latest)
   and download `cambridge-v<version>.apk`.
   In the current `v0.1.1` release, the file is `cambridge-v0.1.1.apk`.
2. Install the APK. Android may ask you to allow installs from the browser or
   file manager used to open it.
3. Open CamBridge and allow camera access when prompted. The app also needs
   network access to discover and stream to the OBS computer.

CamBridge shows a foreground notification while streaming. The notification
includes the Stop control; removing the app task also stops the active stream.

## Linux / OBS

1. Download `cambridge-obs-plugin-<version>-linux-x86_64.tar.gz` from the
   [latest GitHub release](https://github.com/louisemalvin/cambridge/releases/latest).
   In the current `v0.1.1` release, the archive is
   `cambridge-obs-plugin-0.1.1-linux-x86_64.tar.gz`.
2. Extract the archive. It contains the OBS plugin at:

   ```text
   obs-plugins/cambridge-obs-plugin/bin/64bit/cambridge-obs-plugin.so
   ```

3. Install the plugin directory into the per-user OBS plugin directory:

   ```bash
   mkdir -p ~/.config/obs-studio/plugins
   cp -a cambridge-obs-plugin-VERSION-linux-x86_64/obs-plugins/cambridge-obs-plugin \
     ~/.config/obs-studio/plugins/
   ```

   Replace `VERSION` with the version you downloaded. For example, the
   current `v0.1.1` release uses the directory
   `cambridge-obs-plugin-0.1.1-linux-x86_64`.
4. Restart OBS, add a source named **CamBridge**, and keep the default source
   settings for normal use.

## Start a stream

1. Make sure OBS is running with the CamBridge source present.
2. Open CamBridge on the phone and open **Stream setup**.
3. Wait for the receiver check to find the OBS computer.
4. Choose a video quality, frame rate, and landscape or portrait orientation.
5. Press **Start stream**. Press **Stop stream** in the app when finished.

## Network and firewall

Receiver discovery uses the local network. If the phone cannot find OBS, check
that both devices are on the same network, OBS is running, and the Linux
firewall permits CamBridge's control TCP port and media UDP port from the
phone. The default ports are listed in [Protocol](protocol.md), and the OBS
source settings are authoritative if they have been changed.

CamBridge is not encrypted or authenticated. Do not expose these ports to the
internet; see [Known limitations](known-limitations.md).
