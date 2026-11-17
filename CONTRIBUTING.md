# Contributing

## Set up

Use JDK 17 and Android SDK 37. Add your local SDK path to the ignored `local.properties` file:

```properties
sdk.dir=C\:/path/to/Android/Sdk
```

## Check a change

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain
.\gradlew.bat assembleDebugAndroidTest --no-daemon --console=plain
```

Run connected tests on an unlocked emulator or device with the screen kept awake. Keep changes small. Do not add permissions, network services, telemetry, or PDF dependencies without explaining why Android platform APIs are insufficient.

Use Conventional Commits, such as `fix: reject malformed PDF imports`.
