# apksig third-party notice

- apksig (`com.android.tools.build:apksig`) 8.7.3, part of the Android
  SDK build tools. Copyright (C) The Android Open Source Project.
  Licensed under the Apache License, Version 2.0:
  http://www.apache.org/licenses/LICENSE-2.0
- Faraday uses it in exactly one place: the code-and-export exporter
  (Unit 5) signs exported project APKs on the phone with APK Signature
  Scheme v2. The pinned version matches the Android Gradle Plugin
  version in the root build file (8.7.3), per the code-and-export
  scope's fact 7.
- The `:webshell` module and the exporter are Faraday's own code and
  carry no third-party notice.
