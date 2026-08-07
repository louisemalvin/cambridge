# Third-party notices

This is an engineering inventory for the direct Android-to-OBS path.

| Component | Use | License/source |
| --- | --- | --- |
| Android Camera2 and MediaCodec | Camera capture and H.264 encoding | Android SDK terms |
| AndroidX and Jetpack Compose | Android lifecycle and UI | Apache-2.0 |
| Kotlin coroutines and serialization | Async work and control JSON | Apache-2.0 |
| OBS Studio libobs | Native source host and texture API | GPL-2.0-or-later |
| FFmpeg libavcodec/libavutil/libswscale | H.264 decode and software NV12 conversion | LGPL-2.1-or-later or GPL-2.0-or-later depending on build options |
| Mesa/libva and libdrm | VAAPI decode and DRM PRIME/DMA-BUF handles | MIT and related component licenses |
| jansson | Native control JSON parsing | MIT |

Before distributing binaries, generate complete dependency notices for the
Gradle and native build environments and review the selected FFmpeg and OBS
license configurations.
