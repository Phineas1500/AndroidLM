# Running the golden test on a device

`DeviceGoldenTest` (`app/src/androidTest/java/org/androidlm/research/android/`) replays this
module's `GoldenTest` on a phone, over the app's real storage: `AndroidSqlDatabase` (requery
sqlite-android, bundled SQLite 3.49.0 with FTS5) and `AndroidZstd` (zstd-jni). Both tests run the
same comparisons, `GoldenChecks` in `research/src/testFixtures`, against the same `golden.json`.

## 1. Push the fixtures

The three files are the ones `:research:test` uses (`~/androidlm-tools/fixtures` on the build
machine): `golden.json`, `sample_wiki.db`, `sample_voyage.db` (about 25 MB together).

```sh
adb shell mkdir -p /data/local/tmp/androidlm-fixtures
adb push golden.json sample_wiki.db sample_voyage.db /data/local/tmp/androidlm-fixtures/
adb shell chmod 755 /data/local/tmp/androidlm-fixtures
adb shell chmod 644 '/data/local/tmp/androidlm-fixtures/*'
```

Why there: it needs no permission (Android lets apps read, never write, what adb put in
`/data/local/tmp`, provided the mode bits allow it, hence the chmod), and it survives the
uninstall that ends every connected test run. The databases are opened read-only and are in
rollback-journal mode, so SQLite never needs to create a file next to them.

The test also looks in `/sdcard/Android/data/io.github.phineas1500.androidlm.dev/files/fixtures`
(the dev flavor's external files dir; removed together with the app on uninstall), and any other
directory can be named with the `fixturesDir` argument below. When no directory has all three
files readable, every test is reported as skipped (assumption failure), not failed.

## 2. Run

From `app-android/`, with one device attached (on the Mac mini: `nice -n 5 ./gradlew --no-daemon ...`):

```sh
./gradlew :app:connectedDevDebugAndroidTest
# other fixture directory:
./gradlew :app:connectedDevDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.fixturesDir=/sdcard/Download/androidlm-fixtures
```

The HTML report lands under `app/build/reports/androidTests/connected/`. Nine tests are
expected: the seven golden groups (each loops over the 9 cases; 728 comparisons in total, the
count is printed to logcat as `DeviceGoldenTest: 728 golden comparisons passed`), plus
`mainDatabaseIsReadOnly` and `largestBlockReadsFully`.

Without Gradle on the machine the phone is attached to, install the two APKs that
`:app:assembleDevDebug :app:assembleDevDebugAndroidTest` produce and start the runner by hand:

```sh
adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
adb install -r app/build/outputs/apk/androidTest/dev/debug/app-dev-debug-androidTest.apk
adb shell am instrument -w -e class org.androidlm.research.android.DeviceGoldenTest \
    io.github.phineas1500.androidlm.dev.test/androidx.test.runner.AndroidJUnitRunner
```

`OK (9 tests)` is a pass. If the output instead mentions an assumption failure ("golden fixtures
not found"), the fixtures were not found or not readable: check the directory and the chmod.
