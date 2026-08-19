# Third-party notices

This is an engineering inventory for the CamBridge phone-to-OBS paths.

| Component | Use | License/source |
| --- | --- | --- |
| Android Camera2 and MediaCodec | Camera capture and H.264 encoding | Android SDK terms |
| AndroidX and Jetpack Compose | Android lifecycle and UI | Apache-2.0 |
| Kotlin coroutines and serialization | Async work and control JSON | Apache-2.0 |
| OBS Studio libobs | Native source host and texture API | GPL-2.0-or-later |
| FFmpeg libavcodec/libavutil/libswscale | H.264 decode and software NV12 conversion | LGPL-2.1-or-later or GPL-2.0-or-later depending on build options |
| Mesa/libva and libdrm | VAAPI decode and DRM PRIME/DMA-BUF handles | MIT and related component licenses |
| jansson | Native control JSON parsing | MIT |
| VideoToolbox, CoreVideo, IOSurface, Metal, Foundation, and DNS-SD | macOS native H.264 decode, retained surfaces, GPU conversion, OBS import, and Bonjour advertisement | Apple system frameworks and SDK terms |

Before distributing binaries, generate complete dependency notices for the
Gradle and native build environments and review the selected FFmpeg and OBS
license configurations.

Linux plugin packages intentionally contain only the CamBridge module. They do
not bundle OBS, FFmpeg, or their transitive libraries. The Linux build resolves
the installed `libobs` and FFmpeg packages through `pkg-config`, requires OBS
32.2.0 or newer, requires the FFmpeg ABI family used by FFmpeg 8 or newer, and
rejects RPATH/RUNPATH entries so an old library stack cannot shadow the host
runtime.

## Pinned macOS source baselines

The macOS build helper downloads and verifies these source archives from the
versions recorded in `receiver/obs/cambridge-obs-source/buildspec.json`:

| Source | Version | URL | SHA-256 |
| --- | --- | --- | --- |
| OBS Studio | 32.1.2 | [official source archive](https://github.com/obsproject/obs-studio/releases/download/32.1.2/OBS-Studio-32.1.2-Sources.tar.gz) | `c6532380c68a75327fe8b551461adeca8f184dcbe4015096251a6de76362a554` |
| FFmpeg | 7.1.1 | [official source archive](https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz) | `733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1` |

The project Metal conversion shader is source code in
`receiver/obs/cambridge-obs-source/data/macos/nv12_to_bgra.metal`; it is not a
third-party binary. Release signing and notarization credentials are supplied
by the release environment and are never stored here.
