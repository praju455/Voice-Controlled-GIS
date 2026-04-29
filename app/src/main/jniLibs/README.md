# Native SpatiaLite Bundles

Place Android-native SpatiaLite shared libraries here when they are available.

Expected layout:

- `app/src/main/jniLibs/arm64-v8a/libspatialite.so`
- `app/src/main/jniLibs/x86_64/libspatialite.so`

The native bridge will also probe for `mod_spatialite.so` if that is the form you obtain.

Current app ABIs:

- `arm64-v8a`
- `x86_64`

Once these libraries are present, the app's JNI layer will detect them automatically at startup.
