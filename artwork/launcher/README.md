# Faraday launcher icon

`faraday-wisp.svg` is the exact approved face-first Wisp (option 2), retained
without redesign. It is the source for checked-in PNGs in every Android density.
Run `python3 scripts/generate_launcher_icons.py` with Inkscape to regenerate;
ordinary Android builds need only Python, not an image renderer.

All app build types and normal/compact packaging share `app/src/main`.
The manifest selects regular and round launcher icons. API 26+ selects adaptive
background/foreground layers; API 33+ additionally supplies an alpha-only themed
silhouette with transparent eyes and smile. Older/fallback launchers have regular
and circular 48–192 px images. The adaptive canvas is 108 dp, with the approved
1024-square composition mapped to its central 72 dp and 18 dp overscan on each
side. The face/body remain inside the central safe region; decorative glow and
shadow may clip under launcher masks. Background always fills the mask.

`scripts/check_launcher_icons.py` rejects missing/stale artwork, wrong
dimensions/layers, or a variant manifest/resource override. The icon tests run
with the repository CI Python helper suite; Gradle needs no custom generation
step because every app variant includes the checked-in main-source resources.
`python3 -m unittest discover -s scripts -p test_launcher_icons.py` covers both
valid resources and deliberate missing/stale/override failures. Checksums bind
PNGs to the checked-in generation receipt; changing artwork requires explicit
regeneration. This does not replace APK resource compilation or device QA.
