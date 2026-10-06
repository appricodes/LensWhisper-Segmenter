# LensWhisper Object Explorer — API guide

Any Android app can send LensWhisper Object Explorer a photo and get back the objects in it: for
each object, its name, a confidence score, a bounding box, and a pixel mask of its exact outline.
Everything runs on the phone. The companion app has **no internet permission**, so nothing you send
it can leave the device.

Everything in this `api/` folder (this guide, `ISegmenterService.aidl`, `client/SegmenterContract.kt`,
`client/SegmenterClient.kt`) is licensed **Apache-2.0** (see [`LICENSE`](LICENSE)). You may copy it
into any app, open or closed source. The companion app itself is AGPL-3.0, but calling it over this
API doesn't make your app AGPL. The two are separate programs exchanging an image and a result.

- Package: `com.appricodes.lens_whisper.segmenter`
- Bind action: `com.appricodes.lens_whisper.segmenter.action.SEGMENT`
- Current API version: **1**
- Model: YOLOv8m-seg (COCO, 80 classes), int8

## Quick start (Kotlin)

1. Copy `aidl/com/appricodes/lens_whisper/segmenter/ISegmenterService.aidl` into your module's
   `src/main/aidl/com/appricodes/lens_whisper/segmenter/`. The package must stay exactly that,
   because Binder identifies the interface by it. Then turn AIDL on:

   ```kotlin
   android { buildFeatures { aidl = true } }
   ```

2. Copy `client/SegmenterContract.kt` and `client/SegmenterClient.kt` (they need `androidx.core`).

3. Let your app see the companion (Android 11+ package visibility), in `AndroidManifest.xml`:

   ```xml
   <queries>
       <package android:name="com.appricodes.lens_whisper.segmenter" />
   </queries>
   ```

4. Call it from a background thread:

   ```kotlin
   val client = SegmenterClient(context)               // trusts SegmenterContract.RELEASE_CERT_SHA256
   if (client.isAvailable()) {
       val file = File(context.cacheDir, "photo.jpg")    // your own private file
       file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
       val result = client.segment(file)                 // blocks for ~0.5–3 s
       file.delete()
       if (result != null && result.first == SegmenterContract.STATUS_OK) {
           for (obj in result.second) {
               // obj.label, obj.confidence, obj.box, obj.mask / maskWidth / maskHeight
           }
       }
   }
   ```

   `segment` returns `null` when the companion isn't installed, isn't genuine, is too old, or
   doesn't answer within the timeout (15 s by default). Fall back to something else in that case.

If the companion isn't installed, send the user to
`https://play.google.com/store/apps/details?id=com.appricodes.lens_whisper.segmenter`.

## Sending images only to the real app

A photo can be private. Before you send one, you need to be sure the app receiving it is the real
LensWhisper Object Explorer and not a look-alike. A different app can be installed under the same
package name when the real one isn't there, and any app can declare the same intent action.
`SegmenterClient` handles this with four layers:

1. **Explicit binding.** The intent carries `setPackage("com.appricodes.lens_whisper.segmenter")`,
   so Android only delivers it to that package. Other apps that declare the same action are never
   considered.
2. **Signing-certificate check before binding.** Android guarantees that every installed package
   name belongs to exactly one signing key, and only that key's owner can publish updates to it.
   `SegmenterClient` checks the installed package's certificate against a list of trusted SHA-256
   fingerprints using `PackageInfoCompat.hasSignatures(..., CERT_INPUT_SHA256, matchExact = false)`.
   It doesn't bind at all unless one of them matches. `matchExact = false` also accepts a key the
   developer has rotated to with Android's APK signature scheme v3. **An empty list fails closed**:
   nothing is trusted.
3. **A second check once connected.** The certificate is checked again in the window between
   binding and sending, in case the package was replaced in the meantime.
4. **No file or URI another app could open.** The image goes as a read-only
   `ParcelFileDescriptor` opened on a file in *your* private storage, passed over the one Binder
   connection to the verified process. No other app gets a path, a `content://` URI or a
   permission grant.

### Trusted certificates

| Build | SHA-256 of the signing certificate |
|---|---|
| Builds published on GitHub (developer's own key) | `3DF72F3764EF8A0DD5B62D8F13138306DB58336BDAE2664C133A47F334F1F382` |
| Google Play (Play app signing) | `B8913D90287F837FE6E8FA6525DDDF6D7953FECFF4A6B16969C8F8251AC49C71` |

`SegmenterContract.RELEASE_CERT_SHA256` holds the same values. If you build the companion yourself,
pass your own certificate's fingerprint to `SegmenterClient(context, setOf(...))`. You can read a
fingerprint with `apksigner verify --print-certs app.apk` or
`keytool -printcert -jarfile app.apk`.

### Why a bound service and not an intent

| Way to pass the image | Problem |
|---|---|
| Implicit `Intent` / `ACTION_SEND` | Any app can register for it, so the photo may go to whichever app the system or the user picks. |
| `startActivity` with a `content://` URI and `FLAG_GRANT_READ_URI_PERMISSION` | The grant goes to whatever activity resolves. You also need a `FileProvider`, the result has to come back through `onActivityResult`, and a screen opens. |
| Broadcast | Receivers can't be pinned by certificate, and large results don't fit. |
| **Bound service over AIDL, explicit package, certificate pinned, `ParcelFileDescriptor`** | Point-to-point to one verified process, no UI, no file exposed, synchronous result. **This is what the API uses.** |

A signature-level permission (`android:protectionLevel="signature"`) would let *the service* refuse
everyone except apps signed with the same key. That isn't used here, because the service is meant to
be open to every app. It also wouldn't protect *callers* from a fake service. Pinning the
certificate on the caller's side is what does that.

## What the service does to protect itself

The service is open to every app on purpose, so it limits what any caller can do:

- **No internet permission.** You can check this in the installed app's manifest. Images can't be
  uploaded anywhere.
- **Nothing is stored or logged.** The image is read into memory, decoded, segmented and dropped.
- **Size limits.** Encoded images over 30 MB get `STATUS_TOO_LARGE` before decoding. Larger
  pictures are downsampled while decoding so neither side exceeds 2048 px, which stops one huge
  image from using up the phone's memory.
- **One request per app at a time.** A second call from the same app (by UID) while one is running
  gets `STATUS_BUSY`, and so does any call once 4 requests are in progress across all apps.
- **No crash on bad input.** Data that isn't an image gets `STATUS_BAD_IMAGE`.

What it does *not* do: limit which apps may call it. Any app can use it; that's its purpose.

## Reference

```aidl
interface ISegmenterService {
    int getApiVersion();
    Bundle segment(in ParcelFileDescriptor image, in Bundle options);
}
```

`segment` blocks for typically 0.5–3 seconds, so never call it on the main thread. `image` can be
any encoded image format Android can decode (JPEG, PNG, WebP, HEIF, …). EXIF orientation is
**not** applied, so send the pixels upright. Pipes work too.

### Options (`Bundle`, may be `null`)

| Key | Type | Default | Meaning |
|---|---|---|---|
| `minConfidence` | float | 0.25 | Drop objects scored below this (clamped to 0.05–1). |
| `maxInstances` | int | 25 | Return at most this many objects, strongest first (1–25). |

### Result (`Bundle`)

| Key | Type | Meaning |
|---|---|---|
| `status` | int | `0` OK, `1` bad image, `2` too large, `3` busy, `4` internal error. Always present. |
| `message` | String | Short English explanation when `status` isn't 0. |
| `model` | String | e.g. `yolov8m-seg-coco-int8`. |
| `imageWidth`, `imageHeight` | int | The image size as decoded (after any downsampling). |
| `instances` | `ArrayList<Bundle>` | One per object, strongest first. |

Each instance:

| Key | Type | Meaning |
|---|---|---|
| `classId` | int | COCO class index 0–79. Use this rather than the label if you match classes. |
| `label` | String | English COCO name, e.g. `cup`, `tv`, `potted plant`. |
| `confidence` | float | 0–1, the model's per-class (sigmoid) score. |
| `box` | float[4] | left, top, right, bottom, normalized 0–1 to the whole image. |
| `maskWidth`, `maskHeight` | int | Size of the mask grid. |
| `mask` | byte[] | The mask, one bit per grid cell. |

**Mask encoding.** The grid spans the *whole* image: cell `(x, y)` covers normalized
`x/maskWidth … (x+1)/maskWidth` horizontally, and the same for `y`. Cells are stored row-major, one
bit each, most significant bit first: cell `i = y * maskWidth + x` is set when
`(mask[i / 8] >> (7 - i % 8)) & 1 == 1`. `SegmenterContract.unpackMask` does this for you. The grid
is about a quarter of the model's 640-pixel input on its long side (for example 160×90 for a 16:9
photo).

To test whether a point is part of an object, where `nx` and `ny` are normalized 0–1:

```kotlin
val x = (nx * maskWidth).toInt(); val y = (ny * maskHeight).toInt()
val inside = x in 0 until maskWidth && y in 0 until maskHeight && mask[y * maskWidth + x]
```

## Compatibility

The order of methods in the AIDL file is the wire protocol. Methods are never removed or reordered.
New ones are only added at the end, and `getApiVersion()` goes up when they are. Result keys are only
ever added, never renamed. Ignore keys you don't know.
