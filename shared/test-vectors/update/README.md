Test vectors for update manifest checks (Android net/UpdateManifest.kt, Windows Handler/UpdateManifest.cs).
The keys here are throw-away test keys made only for these tests; they never sign real releases.
- manifest.json + manifest.json.sig: signed with the key in test-key.pub.b64 (ECDSA P-256, SHA-256, DER signature).
- manifest.other.sig: the same manifest signed with another key (other-key.pub.b64): must be rejected with test-key.
- payload.bin: the file listed as FlowVeil-android.apk in the manifest.
