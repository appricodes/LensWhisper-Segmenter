# LensWhisper Object Explorer

An Android app that finds the objects in a photo — their names, colours and exact outlines —
entirely on the phone, built for blind and low-vision users with TalkBack. It has three buttons:

- **Detect Objects** — names the objects in view.
- **Identify Objects and Colors** — names each object and its main colours.
- **Explore Photo by Touch** — the photo fills the screen; moving a finger over it names the object
  under the finger.

Other apps can use it too: they send it a photo and get every object's name, confidence, box and
pixel mask back. [LensWhisper](https://play.google.com/store/apps/details?id=com.appricodes.lens_whisper)
uses it automatically when it is installed. **For developers: see [`api/API.md`](api/API.md)**,
including how to make sure your images only ever reach the genuine app.

The app has **no internet permission**: nothing it is shown can leave the phone.

## Building

Android Studio, or JDK 17+ and the Android SDK (minSdk 26, compileSdk 36):

```bash
./gradlew :app:assembleDebug
```

Release builds are signed when a `keystore.properties` file (storeFile, storePassword, keyAlias,
keyPassword) is present at the repository root; it is gitignored.

## Layout

| Path | What | Licence |
|---|---|---|
| `app/` | The app | AGPL-3.0-or-later |
| `api/` | The service's AIDL, a client and its contract, and the API guide | Apache-2.0 |
| `app/src/main/assets/models/yolov8m_seg_int8.tflite` | YOLOv8m-seg by Ultralytics | AGPL-3.0 |

## Licence

The app is free software under the **GNU Affero General Public License v3.0 or later** — see
[LICENSE](LICENSE). The `api/` folder is Apache-2.0 (see [api/LICENSE](api/LICENSE)) so other apps,
open or closed, can copy it to talk to the service. [NOTICE](NOTICE) lists the third-party
components.

Detection results are AI guesses and can be wrong. Never rely on them for safety, health or money.
