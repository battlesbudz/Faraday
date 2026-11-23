# Third-party notices

## Mika ADB pairing primitives

`AdbEd25519.kt`, `AdbSpake2.kt`, and `AdbPairingAuth.kt` are adaptations of
the corresponding files in Mika by Wang Zan (Apache License 2.0), at
https://github.com/wangzan101/Mika/tree/2ca391c4c868/core/src/main/kotlin/com/wangzan101/mika/core/adb.
The package was changed for Faraday and attribution comments were added. A copy of Apache License
2.0 is included at `app/src/main/assets/licenses/smart-turn/Apache-2.0.txt`;
the upstream LICENSE is at https://github.com/wangzan101/Mika/blob/2ca391c4c868/LICENSE.

## Mika shell-helper handoff reference

`PrivilegeHelperMain.kt` adapts the app_process handoff pattern and shell-context
bootstrap from Mika's `HelperProcess.kt` and `ShellContext.kt` (Wang Zan,
Apache License 2.0), referenced at
https://github.com/wangzan101/Mika/tree/2ca391c4c868/app/src/main/java/com/wangzan101/mika/cap/phone/shell.
Faraday's package names and AIDL backend are its own; the handoff protocol is
adapted for the chartered nonce/provider contract. The full license is included
at `app/src/main/assets/licenses/smart-turn/Apache-2.0.txt`.
