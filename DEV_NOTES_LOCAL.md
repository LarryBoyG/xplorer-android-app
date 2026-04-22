# XIRO Lite Local Dev Notes

## Version: v0.4.0-beta

### App Version
- versionName: 0.4.0-beta
- versionCode: 100

### Android Target
- compileSdk: 36 (Android 16)
- targetSdk: 36 (Android 16)

### Current Focus
- XIRO Xplorer compatibility restoration
- legacy-app-aligned telemetry decoding
- live-view stability and HUD polish
- camera library browsing and download workflow
- range extender bind and settings parity

### Recent Changes
- Remote controller battery is now read through the recovered legacy TCP 6666 `getRemoteElectricity` callback, using request `1A 06 AC 06 D2` and the calibrated raw-to-percent mapping validated from 40%, 60%, 80%, and 100% captures.
- Storage setup now shows a XIRO-styled in-app permission prompt before attempting to create the shared XIRO folder tree, instead of jumping to Android storage settings during launch.
- Users can continue with app-private storage for the current session if they do not grant shared storage access, keeping previews, downloads, offline maps, and HJ logs functional.
- Live View RTSP now forces Media3 stream sockets onto the connected Wi-Fi network when possible, preventing Android from accidentally routing camera video over mobile data on phones with an active cellular plan.
- XIRO network detection now scans connected Wi-Fi networks instead of trusting only Android's active/default network, so the app can still recognize the extender when cellular remains the preferred internet route.
- The launcher icon now correctly uses the mountain-and-radar artwork from the approved XIRO Lite icon concept, replacing the accidentally wired placeholder icon from the previous build attempt.
- The launch splash screen now uses the new ReDiscover Your Sky artwork, with the slogan overlaid directly in the splash layout for a stronger first-open identity.
- The splash slogan now reads `ReDiscover / Your / Sky`, with the `Re` highlighted in XIRO green to match the preserved app branding.
- The app launcher icon set was replaced with the new dark green landscape/radar icon and regenerated across the Android mipmap densities, including adaptive-icon foreground assets.
- The adaptive launcher icon background was darkened so the new icon art sits cleanly without inheriting the old bright green grid backdrop.
- The app now shows a mandatory launch disclaimer on every startup, and users must explicitly agree before continuing to use XIRO Lite.
- The launch disclaimer cannot be dismissed accidentally by tapping outside or pressing back, and it now offers a clear exit path for users who do not want to continue.
- The startup flow now waits until the disclaimer is accepted before showing the What's New dialog, keeping launch messaging clearer and more deliberate.
- The XIRO Lite header tagline now reads `ReDiscover Your Sky`, with the `Re` highlighted in XIRO green for a cleaner preservation-project identity.
- Live View now keeps alert banners at the bottom of the screen, which clears the top edge for telemetry chips and reduces obstruction of the camera feed.
- The customizable Live View HUD now stays on a single row: chips compact themselves as more items are enabled and fall back to horizontal scrolling instead of wrapping onto a second line.
- Offline maps now include a visible legend for Drone, You, and Home so the colored markers are easier to interpret in both Live View and replay-map use.
- XIRO Lite now shows an over-height warning when live Elevation exceeds the validated legal-height threshold, with the warning text automatically matching the app's metric or imperial unit setting.
- Target Elevation now uses stricter sanity filtering so bogus target-height spikes like the earlier 700m+ ground-state values are rejected instead of reaching the HUD.
- Taking a photo or starting/stopping video now keeps a freeze-frame of the last good live-view image on screen while RTSP is interrupted, reducing the black-screen gap during camera commands.
- Camera SD thumbnails are now cached into the XIRO `Preview` folder so reopening the Library tab does not have to re-fetch every remote preview over Wi-Fi.
- Preview-cache entries no longer appear as fake local downloads: the Local tab is now HD-only and represents only media that has actually been saved into the XIRO `HD` folder.
- Remote preview caching now tags photo and video thumbnails separately so video preview images can still be identified correctly without creating duplicate Local entries.
- The extender password wording was clarified to `Wi-Fi extender password`, and the root Settings page no longer shows the confusing `Blank` value or echoes the typed password back after editing.
- Live View now uses the PHOTO / VIDEO mode selector itself as the mode toggle, which frees up space by removing the older dedicated switch button.
- A new gear menu in Live View now holds the stream selection, giving us a cleaner home for future camera controls like ISO or brightness.
- The live-view map inset is smaller, uses a compact attribution badge, and the inset tap-to-swap behavior was tightened up so swapping between live and map views is more reliable.
- The HJ replay player now includes an offline map panel that can render the saved breadcrumb path, the current replay point, and the recovered home point using the same mapsforge/OSM-backed offline maps as Live View.
- Camera SD previews now use the same width-aware grid sizing as local media, and remote videos now try best-effort thumbnail generation before falling back to plain placeholders.
- Elevation decoding now treats the recovered Baro/Target height fields as signed values and preserves the last sane reading, preventing the old 6000 m spike bug from blasting the HUD.
- The app now uses a shared skeuomorphic design system with raised dark surfaces, white primary text, green accent states, and more polished inset controls across the main shell.
- Settings dialogs, library overlays, progress/info popups, and the HJ past-flight replay viewer were restyled to match the new 3D visual language instead of mixing old flat Material surfaces with the newer cards.
- Live View now shares the same updated design language, including refined control surfaces and debug overlays, while keeping the existing bottom navigation behavior and sliding tab animations intact.
- Telemetry now decodes live GPS coordinates from the HJ-compatible UDP packet layout, matching the same latitude/longitude positions previously validated through XIRO Assistant log playback.
- The Telemetry page now derives live speed and distance from the packet stream instead of leaving those fields blank, so motion becomes visible as soon as GPS coordinates begin changing in flight.
- Wi-Fi telemetry now reflects only the relay-to-camera signal reported by the extender, so the Telemetry page does not mix in the phone's own Wi-Fi strength.
- Top-bar telemetry labels are now cleaner and more honest: relay is shown as Wi-Fi, and SD Card / FOV no longer pretend to be decoded when they are still camera-side pending fields.
- Wi-Fi telemetry now renders with traditional signal-strength bars so the extender-to-camera link is easier to read at a glance.
- Preflight status rows now include live-view HUD toggles, so selected telemetry items can be added or removed from the live-view overlay without changing code.
- GPS Sat, Aircraft Power, and Flight Mode now use richer status colors in both the Preflight card and the live-view HUD, matching the requested red / yellow / green thresholds.
- Live View now uses a simple left-arrow exit control, and the Settings connection card text was cleaned up to reduce clutter.
- The live-view HUD now sits in line with the exit control instead of dropping lower into the image area, making the selected telemetry chips easier to read while flying.
- The Library tab now gives local media a more traditional gallery feel with swipe navigation, local video playback controls, and first-frame thumbnails for downloaded videos.
- Remote camera photos now try legacy-style preview candidates before download, and the main navigation now slides both the page content and the selected tab highlight.
- Exiting Live View while a video recording is active now sends a stop-video command first, matching the legacy app more closely and reducing the chance of half-finished video files.
- Live View now uses a proper camera-style shutter control: photo mode gets a white dual-ring capture button with press feedback, and video mode gets a red record button with a live elapsed timer while recording.
- The Telemetry page now treats Wi-Fi strictly as the extender-to-camera link and no longer falls back to the phone's own Wi-Fi strength.
- Camera SD library parsing now strips query-style suffixes like `?del=1` before treating entries as real media, reducing ghost duplicates and unsafe remote-media handling.
- Settings now include an app-wide units toggle, so Telemetry, Live View, and HJ flight-log replay can switch between metric and imperial distance/speed readouts without drifting out of sync.
- Settings subpages now use simpler left-arrow navigation, and the root Settings screen keeps `About` and `Release Notes` grouped at the bottom instead of scattering extra cards through the page.
- Past Flights now auto-populates from the XIRO `hj` folder without a manual refresh card, and the replay player was simplified to a cleaner left-arrow exit and a single play/pause control.
- The camera home screen icon now centers more reliably across different device sizes and uses profile-aware iconography for drone/extender versus direct-camera connections.
- Live View capture feedback now uses cleaner non-debug wording and a stronger shutter / record start-stop sound path.
- The Camera tab hero now sits truly centered in the available camera pane, and the Live View right-side control rail is back in a stable right-edge layout.
- Live View warning banners and the photo/video selector now size to their content instead of stretching across the full screen width.
- Compass calibration failure is now decoded from the legacy remote alarm bitmask at `UDP[77]`, matching the legacy SDK `MAGNETIC_ERROR = 2` flag and the captured compass-failure pcap.
- The Library tab now hides low-level Camera SD command diagnostics in normal use, so empty-card and disconnected states stay cleaner unless Debug Mode is enabled.
- Local Library tiles now size from the available card width instead of using a rigid thumbnail size, giving the grid a more symmetrical layout with less dead space on the right.
- Telemetry now exposes decoded `Elevation` and `Target Elevation` fields from the recovered Baro/Target height paths, and Live View HUD selections automatically migrate the older `Altitude` and `Baro HGT` toggles to `Elevation`.
- Live View now supports a legacy-style offline map swap: a small bottom-left map inset can be tapped to expand full-screen, and the live video becomes the preview inset so you can swap back instantly.
- Settings now include an `Offline Maps` page for opening the official mapsforge download source, importing local `.map` regions, choosing the active region, and showing the required OpenStreetMap/mapsforge attribution in-app.

### Known Issues
- SD Card Storage Remaining still appears to come from a separate camera callback path, so the Telemetry page shows it as pending until that transport is decoded cleanly.
- FOV in the legacy app appears to be a camera-side field-of-view or digital zoom callback, not core flight telemetry, so XIRO Lite is leaving it pending until the camera-side query is mapped.
- Xplorer 4K still needs real hardware validation to confirm whether its live-view timing matches the regular Xplorer and Gimbal flow.
- Xplorer 4K time sync is still intentionally excluded until its Ambarella-side transport is captured cleanly.
- Freshly reset range extenders still need real hardware verification to confirm the full bind flow matches the legacy app across firmware variants.
- Extender rename and extender password changes still rely on the best-effort HTTP transport because the exact legacy rename transport split has not been fully proven on-wire yet.
- Current bound camera SSID may still remain unavailable on some relay firmware revisions until every current-air response variant is decoded.
- Remote camera-SD delete is still intentionally excluded until the true legacy transport is proven.
- Remote video thumbnail and screennail transport is still not fully decoded, so remote camera videos remain download-first instead of preview-first.
- Remote media info is still best-effort and may show `Unknown` when the camera does not return reliable file headers or stream metadata.
- Remote battery percentage is newly decoded from a calibrated legacy callback table and should be treated as experimental until more low-end remote battery captures are validated.
- Live View camera mode is still inferred locally; the legacy current-mode callback exists, but its on-wire transport has not been decoded yet.
- The first map release uses offline region files only, so users still need to download/import their own `.map` files before the map inset can render real geography in Live View.
- The current flight-mode HUD label still follows the validated legacy pattern of `0 sats = Attitude` and `nonzero sats = GPS Mode`, but deeper control-state decoding is still in progress.
- `.hj` logging now auto-starts in live view, but broader whole-app session logging outside the viewer may still need a dedicated recorder lifecycle later.
- UAV-time sync transport is still intentionally excluded until the separate legacy flight-control path is proven on-wire.
- Deeper legacy warnings like return-home and optical-flow fault are still intentionally excluded until their raw fields are validated.
- XIRO Lite still approximates the legacy keepalive by refreshing the RTSP session proactively instead of issuing true RTSP GET_PARAMETER on the active ExoPlayer session.

### Release Checklist
- Bump `versionCode` and `versionName` in `app/build.gradle.kts`
- Update the version header and change list in `DEV_NOTES_LOCAL.md`
- Update `currentReleaseNotes()` in `app/src/main/java/com/example/xirolite/MainActivity.kt`
- Verify `currentAboutInfo()` in `app/src/main/java/com/example/xirolite/MainActivity.kt` still reflects the correct project status, URLs, and About-page messaging
- Run `./gradlew.bat assembleDebug` before handing off a new APK
