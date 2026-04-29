# XIRO Lite Local Dev Notes

## Current Version: v0.4.24-beta

### App Version
- versionName: 0.4.24-beta
- versionCode: 124

### Android Target
- compileSdk: 36 (Android 16)
- targetSdk: 36 (Android 16)

### Current Focus
- XIRO Xplorer compatibility restoration
- legacy-app-aligned telemetry decoding
- live-view stability and HUD polish
- camera library browsing and download workflow
- range extender bind and settings parity

## Version History
Each release entry should answer three questions: what changed for users, what was updated internally, and what was fixed. Early `0.3.x` history is reconstructed from local project history; `v0.3.97-beta` is the first formal GitHub beta tag.

### v0.4.22-beta
- Changed: Live View now logs the full ordered list of available H.264 decoders and the exact decoder that Media3 actually initializes on-device, so future distortion captures can be tied to a real codec path instead of guesswork.
- Changed: The XIRO H.264 RTP reader now treats aggregated STAP-A packets as real keyframe candidates, detects embedded SPS/PPS/IDR units, and flags those access units as keyframes instead of always labeling them non-key.
- Fixed: After RTP packet loss, the reader now distinguishes between loss inside an in-flight access unit versus loss between access-unit boundaries, dropping the damaged unit cleanly while still forcing a wait for the next keyframe.
- Fixed: The new flashing-between-distortion-patterns clue strongly pointed to repeated keyframe/resync trouble, so this release hardens the exact SPS/PPS/IDR recovery path rather than only tweaking RTSP transport timing.

### v0.4.24-beta
- Changed: The Live Camera Settings transport selector is now hidden from normal users and only appears when debug mode is enabled, keeping the public Live View UI focused on the legacy-faithful UDP path.
- Changed: Opening the Live Camera Settings panel now hides the right-side PHOTO/VIDEO mode switcher and shutter control, so the settings dialog no longer has capture controls floating across it.
- Changed: The System Settings tab now records the latest factory-reset capture finding directly in the UI, marking Camera Factory Reset as an observed legacy action tied to cmd 3081 instead of leaving it completely unknown.
- Fixed: The settings panel no longer competes visually with the live shutter controls shown in the screenshot, making the overlay read like a dedicated dialog instead of a partial layer over active capture controls.

### v0.4.23-beta
- Changed: XIRO Lite's local RTSP UDP transport now accepts full-size XIRO camera datagrams instead of relying on Media3's smaller default UDP packet buffer, which was a poor fit for the camera's repeated ~8200 byte H.264 payloads.
- Changed: Dedicated Live View now asks Android for a much larger UDP socket receive buffer on the XIRO RTP path and logs the actual socket-buffer size that the OS granted.
- Fixed: Fresh UDP-first captures showed sustained H.264 traffic with zero tcpdump-level loss while the picture still arrived green and patchy, pointing away from missing transport and toward packet truncation or app-side socket pressure.
- Fixed: TCP-first still barely receives any real interleaved media from the camera, so this release focuses on the UDP path that actually carries video and removes a likely source of silent H.264 datagram truncation there.

### v0.4.21-beta
- Changed: The verified Live View camera settings are now actually interactive inside the gear-panel UI instead of being read-only evidence chips.
- Changed: Preview Resolution now sends the captured legacy `cmd 2010` stream-size request wrapped in the same `cmd 2015` apply sequence the original app uses, then rebuilds RTSP so the new preview stream can take effect.
- Changed: Image Resolution and Anti-blink now send their decoded legacy commands directly from Live View, with `cmd 1002` handling still-photo size and `cmd 3080` handling the 50Hz/60Hz Anti-blink toggle.
- Fixed: Preview Resolution, Image Resolution, and Anti-blink no longer look "unclickable" in the Live View camera-settings panel; only the still-unknown legacy settings remain intentionally grayed out.

### v0.4.17-beta
- Changed: XIRO Lite now reproduces the legacy app's client-side UDP path priming on XIRO RTSP `SETUP` responses by sending the same `CE FA ED FE` punch packet from the bound RTP client port toward the camera's announced `server_port`, then repeating it once after a short delay.
- Changed: The legacy-style UDP punch is wired into the local RTSP fork itself, so it happens at the correct transport layer and timing instead of being approximated from app UI code.
- Fixed: Full rooted extender captures showed the legacy app sends two client RTP packets to `192.168.1.254:6970` immediately after UDP `SETUP`, while XIRO Lite sent none from its announced client port.
- Fixed: That missing client-side UDP punch likely prevented the extender path from opening or maintaining the relay pinhole for incoming RTP, which cleanly matches the long-running pattern of direct-camera mode working while extender mode black-screened.

### v0.4.18-beta
- Changed: XIRO Lite now reinjects the cached H.264 SPS/PPS initialization NAL units before recovery IDR keyframes, so the decoder gets a fresh codec context again after startup resync or packet damage.
- Changed: The local RTP H.264 reader now marks SPS/PPS reinjection as required whenever it seeks or sees RTP sequence loss, then clears that requirement only after a clean keyframe is committed.
- Fixed: Fresh XIRO Lite captures finally showed real extender and direct-camera video arriving, but with green/blocky corruption that matched decoder resync trouble more than a total transport failure.
- Fixed: Packet analysis showed XIRO Lite was seeing a small amount of real RTP loss while the legacy app stayed clean, so this release focuses on helping the decoder recover cleanly on the next IDR instead of continuing with stale codec state.

### v0.4.19-beta
- Changed: Live View now prefers software H.264 decoders when available for the XIRO RTSP stream, instead of always taking the phone's default hardware AVC decoder first.
- Changed: Dedicated RTSP playback now disables asynchronous MediaCodec queueing and enables decoder fallback, making the decoder path more conservative for the XIRO camera's older live stream.
- Fixed: Fresh captures and the camera's SDP confirmed the stream is truly `H264/90000` with a 320x240 SPS, so the remaining green/patched picture points more toward decoder-path behavior than an H.265/H.264 family mismatch.
- Fixed: This release specifically targets the Android 16 `c2.qti.avc.decoder` hardware path as a likely source of the persistent partial-green output now that real video is finally arriving over both direct-camera and extender sessions.

### v0.4.20-beta
- Changed: Live View now opens a real camera-settings panel from the gear icon instead of the old one-line stream dropdown, giving the viewer a legacy-style home for stream controls and future camera tuning.
- Changed: Added the four legacy XIRO camera-setting tabs inside Live View: Camera Parameter, Picture Parameter, Photograph Setting, and System settings.
- Changed: The new panel preloads the legacy labels, default/current values, and capture findings so verified items stand out while still-undecoded controls stay grayed out.
- Changed: Added captured legacy findings directly into the panel, including Preview Resolution as the live RTSP stream-size control, Image Resolution as a still-photo command path, and Anti-blink as the confirmed 50Hz/60Hz toggle.
- Fixed: XIRO Lite now has an in-app place to document and compare what we have learned from legacy captures without hiding that some camera commands are still pending and intentionally not wired yet.

### v0.4.16-beta
- Changed: Dedicated Live View now reads repeater status through the relay's existing socket/session path only, so the Wi-Fi link indicator can still update without firing the repeater's failing HTTP JSON fallback endpoints during flight.
- Changed: The main app no longer background-polls repeater root status from the Camera, Telemetry, or Library tabs; repeater auto-refresh is now limited to the Settings root page where that information is actually needed.
- Fixed: Fresh extender captures showed dedicated Live View itself was triggering repeated `POST /`, `POST /cgi-bin/relay`, `POST /relay`, and `POST /call` requests that only produced `400/404` responses from `192.168.2.254` every few seconds.
- Fixed: The same extender captures showed those failed relay-management probes overlapping RTSP startup and recovery windows, so this release removes that HTTP relay churn from normal Live View sessions.

### v0.4.14-beta
- Changed: Relaxed the TCP-interleaved `Stability` profile so Live View now holds TCP RTSP sessions open much longer before deciding the stream is dead.
- Changed: Increased TCP startup, stale-frame, and buffering recovery windows to better match the slower first-frame behavior seen in the last known-good `v0.3.97` capture.
- Changed: Automatic recovery retries now wait much longer before hard-restarting a no-frame TCP stability session, instead of churning the player every ~12 seconds.
- Changed: Added a shared Live View activity registry so the main app can see when the dedicated viewer is open.
- Fixed: Suspended MainActivity background profile-detection, camera-storage polling, and relay-root probe loops whenever dedicated Live View is active, reducing hidden camera traffic during RTSP startup.
- Fixed: Backed away from the earlier assumption that an 8 second packet timeout was safe for TCP interleaved mode; UDP remains aggressive, but TCP now gets a more patient session model.

### v0.4.15-beta
- Changed: Hardened the local Media3 H.264 RTP reader so XIRO Lite now buffers whole access units before committing them to the decoder, instead of drip-feeding partial live-view fragments directly into sample output.
- Changed: Live View now waits for the first clean IDR keyframe before showing H.264 video, matching the legacy app's visible `needFirstIFrame` / `gotFirstIFrame` behavior more closely during startup.
- Fixed: If an RTP sequence gap damages a fragmented H.264 access unit, XIRO Lite now drops the rest of that broken unit and waits for the next keyframe instead of smearing corrupted slices across the live picture.
- Fixed: Fresh direct-camera captures showed XIRO Lite now receives nearly legacy-level UDP RTP volume again, so this release shifts from "make media arrive" to "make arriving media recover cleanly after packet loss or dirty startup."

### v0.3.x Foundation (pre-GitHub)
- Changed: Created the clean-room Android project for XIRO Xplorer compatibility research, with profile-aware camera/extender connection handling.
- Changed: Added the main Camera, Telemetry, Library, and Settings navigation model.
- Changed: Added initial XIRO camera command transport for photo, video start/stop, clock sync, library refresh, and camera recovery checks.
- Changed: Added the first RTSP Live View path for `rtsp://192.168.1.254/xxxx.mov`.
- Changed: Added early local media/library browsing and the shared XIRO storage folder layout.
- Changed: Added early range-extender settings and bind-flow research surfaces.
- Changed: Added early telemetry parsing from UDP 6800 and XIRO Assistant/HJ log comparisons.
- Fixed: Removed unsafe assumptions around phone Wi-Fi signal being the same as extender-to-camera signal.
- Fixed: Reduced ambiguity in Settings connection text and hid advanced IP details from normal use.

### v0.3.97-beta
- Changed: Prepared the first GitHub beta release with project metadata, release notes, disclaimer messaging, and open-source attribution work.
- Changed: Rebranded the app around the `ReDiscover Your Sky` identity with the new splash artwork and launcher icon.
- Changed: Added the mandatory startup disclaimer and startup ordering so users must agree before continuing.
- Changed: Applied the shared skeuomorphic dark/green design language across the app shell, Settings, dialogs, Library, Live View, and HJ replay.
- Changed: Added customizable Live View HUD toggles from the Telemetry/Status card.
- Changed: Added single-row compact Live View telemetry chips, bottom alert banners, and the left-arrow Live View exit control.
- Changed: Added camera-style shutter/record controls, including freeze-frame coverage while camera commands interrupt RTSP.
- Changed: Added local gallery improvements with photo swipe navigation, video controls, and first-frame thumbnails for downloaded videos.
- Changed: Added remote preview caching into the XIRO `Preview` folder and kept the Local tab HD-only.
- Changed: Added offline maps using mapsforge/OpenStreetMap-compatible `.map` files, with attribution, map inset swapping in Live View, and map support in HJ replay.
- Changed: Added Past Flights from `.hj` files, with a simplified replay player and telemetry playback.
- Changed: Added metric/imperial unit switching for Telemetry, Live View, and replay displays.
- Changed: Added elevation and target elevation display from recovered Baro/Target height fields.
- Fixed: Fixed the earlier Live View HUD positioning issue where chips drifted down below the exit arrow.
- Fixed: Fixed Library tile symmetry and width-aware sizing for local and Camera SD grids.
- Fixed: Fixed query suffixes like `?del=1` being treated as duplicate real media entries.
- Fixed: Fixed Live View controls stretching across the screen after the design refresh.
- Fixed: Fixed the 6000m+ elevation spike by signed decoding and sanity filtering.
- Fixed: Fixed the target elevation 700m+ ground-state spike by stricter target-height filtering.

### v0.4.0-beta
- Changed: Added recovered remote-controller battery decoding through the legacy TCP 6666 `getRemoteElectricity` callback using request `1A 06 AC 06 D2`.
- Changed: Calibrated remote battery percent mapping from captured 40%, 60%, 80%, and 100% logs.
- Changed: Added SD Card Remaining storage from recovered camera `CMD 1003` photo count and `CMD 2009` video time.
- Changed: Added compass calibration failure detection from legacy remote alarm bitmask `UDP[77]` / `MAGNETIC_ERROR = 2`.
- Changed: Added aircraft low-battery warning language aligned with legacy return-home behavior.
- Changed: Added over-height warning when live elevation exceeds the validated legal-height threshold.
- Changed: Added field-observed flight-mode threshold handling: `0-6 sats = Attitude`, `7+ sats = GPS Mode`.
- Changed: Added cleaner debug exports with live telemetry snapshots, recent UDP packets, command history, and HUD state.
- Fixed: Fixed SD Card remaining display so it no longer relied on the older `3014/3015` guess.
- Fixed: Fixed stale Camera SD command diagnostics leaking into normal Library screens.
- Fixed: Fixed compass calibration warning parity using captured legacy app logs and pcap evidence.

### v0.4.1-beta
- Changed: Added XIRO-styled storage setup prompt before shared folder-tree creation.
- Changed: Added app-private storage fallback when shared storage is not granted.
- Changed: Added XIRO network detection that scans connected Wi-Fi networks instead of trusting Android's active/default route.
- Changed: Forced Media3 stream sockets onto the connected XIRO Wi-Fi network where possible to help phones with active cellular data.
- Changed: Changed Live View `Auto` stream mode to captured legacy-style UDP RTP by default, with TCP-interleaved retained as `Stability`.
- Changed: Removed the old fixed 26-second proactive RTSP rebuild from legacy UDP stream mode.
- Changed: Made stale/buffering RTSP recovery windows more tolerant so short weak-link moments do not immediately tear down playback.
- Changed: Kept the remote battery TCP 6666 relay channel open and reused it for repeat polling, closer to the legacy app's long-lived relay behavior.
- Changed: Confirmed UDP bytes `24..29` are HJ-compatible UTC timestamp fields and updated debug/research labels.
- Fixed: Fixed Android routing failure where camera video could accidentally try the cellular/default route instead of the XIRO Wi-Fi route.
- Fixed: Fixed misleading legacy telemetry hints that treated timestamp bytes as state fields.

### v0.4.2-beta
- Changed: Added Media3 RTSP request/response logging in Debug Mode so future logcat captures can verify ExoPlayer keepalive behavior.
- Changed: Added explicit telemetry freshness tracking for UDP 6800 packets.
- Changed: Live View now marks GPS, flight mode, aircraft power, gear, elevation, target elevation, speed, and distance as stale when UDP telemetry stops updating.
- Changed: Added `Telemetry Stale` and `Telemetry Lost` warnings so old flight-critical values do not remain green during link loss.
- Changed: Live View RTSP now waits for the XIRO Wi-Fi route when streaming to private camera IPs instead of falling back to Android's default route.
- Changed: Live View camera clock sync now waits until after the first live frame plus a short stabilization delay.
- Fixed: Fixed stale GPS lock and aircraft status values remaining visible after UDP 6800 packets stopped for more than 30 seconds.
- Fixed: Fixed impossible no-lock distance/map spikes by ignoring GPS coordinates when the aircraft reports no valid satellite lock.
- Fixed: Fixed the `ENETUNREACH` route failure pattern seen when RTSP fell back to Android's default network.
- Fixed: Reduced startup contention between RTSP setup and HTTP camera clock-sync commands.

### v0.4.3-beta
- Changed: Replaced the stock Media3 RTSP artifact with a local patched `media3-exoplayer-rtsp-xiro` module so XIRO-specific RTSP behavior can be adjusted without a sidecar socket.
- Changed: Added legacy-app-matched active-session RTSP keepalive: after `PLAY`, XIRO Lite now sends `GET_PARAMETER rtsp://192.168.1.254/xxxx.mov/track1` about every 3 seconds on Media3's active RTSP control session.
- Changed: Legacy UDP stream recovery now waits longer during buffering before tearing down, matching the slower real-world startup/rebuffer behavior seen in RTSP debug exports.
- Fixed: Removed the previous mismatch where Media3's default keepalive sent `OPTIONS` at `sessionTimeout / 2` instead of the legacy app's 3-second `GET_PARAMETER` cadence.
- Fixed: Fixed automatic RTSP recovery getting stuck in repeated soft reloads by hard-cycling the player off and back on, closer to the manual exit/re-enter path that recovered video during testing.

### v0.4.4-beta
- Fixed: Corrected XIRO RTSP keepalive timing so the first active-session `GET_PARAMETER` is sent immediately after the `PLAY` response, then continues about every 3 seconds, matching the fresh legacy PCAP timing.
- Fixed: Reduced the chance of the camera ending the live RTSP session right after the first successful frame because XIRO Lite waited too long before sending the first legacy keepalive.

### v0.4.5-beta
- Changed: Live View now binds the process to the selected XIRO Wi-Fi route while the RTSP player is active, so Media3's UDP RTP video sockets use the same Wi-Fi path as the RTSP control socket instead of relying on Android's default route.
- Changed: Added a startup RTSP watchdog for the black-screen case where playback remains in `BUFFERING` and no live frame ever renders.
- Changed: Added the normal Android `CHANGE_NETWORK_STATE` permission needed by some devices/ROMs for process-level network binding.
- Fixed: Fixed the new `0.4.4` failure pattern where telemetry/camera commands stayed alive but the Live View could remain black because UDP RTP startup did not recover.

### v0.4.6-beta
- Changed: XIRO Xplorer Live View now delays RTSP startup long enough to run a legacy-style camera init sequence first, including clock sync plus the recovered `CMD 2009`, `CMD 2031 par=2`, `CMD 1003`, and `CMD 3012` priming requests.
- Changed: XIRO Xplorer hard RTSP restarts now replay that same legacy camera init sequence before rebuilding the player, instead of restarting RTSP alone.
- Fixed: Fixed the fresh `2026-04-23` black-screen startup pattern where XIRO Lite opened Live View, maintained control/status traffic, and even fell back to TCP-interleaved RTSP, but still never matched the legacy app's camera-startup handshake.

### v0.4.7-beta
- Changed: XIRO Xplorer Live View now sends the recovered legacy preview kick right after startup by issuing `CMD 3001 par=1` and `CMD 2016`, matching the legacy app's move into live video mode more closely.
- Changed: XIRO Lite now allows the legacy UDP RTP session more time to deliver a first frame before Media3 treats missing RTP as end-of-input, avoiding the repeated ~12-second teardown loop seen in fresh captures.
- Changed: XIRO Xplorer Live View now automatically falls back from legacy UDP to the `Stability` TCP-interleaved stream profile after a no-first-frame startup failure instead of endlessly retrying the same dead UDP path.
- Fixed: Fixed the newer `2026-04-23` XIRO Lite startup pattern where legacy priming traffic was present, but the app still black-screened because it never sent the legacy video-preview kick and RTSP sessions recycled every ~12 seconds.

### v0.4.8-beta
- Changed: XIRO Lite now schedules the legacy live-preview `CMD 3001 par=1` and `CMD 2016` kick from the same guaranteed startup and hard-restart path that re-enables RTSP, instead of relying on a separate delayed Compose side effect.
- Changed: XIRO Xplorer 4K now uses the same delayed RTSP startup, preview kick, stability toggle, and automatic TCP fallback path as the standard XIRO Xplorer profile.
- Fixed: Fixed the fresh `2026-04-23` capture pattern where XIRO Lite still never placed `3001 par=1` or `2016` on the wire even though the source code for the delayed preview kick existed locally.

### v0.4.9-beta
- Changed: XIRO Xplorer Live View now polls `CMD 3012` as its steady live-view keepalive while the viewer is active, closer to the legacy app's repeated in-view camera polling pattern.
- Changed: XIRO Lite now stops the viewer's repeated `1003 / 2009 / 3014` storage polling while legacy live view is active, reducing camera-side HTTP churn during RTSP startup and playback.
- Changed: The main app now suspends its background profile-detection, camera-storage, and relay-settings probe loops whenever the main activity is no longer in the foreground, so opening dedicated Live View no longer leaves hidden camera probes running behind it.
- Fixed: Fixed the fresh `2026-04-23` capture pattern where dedicated Live View still had extra main-screen traffic like `get_amba_4k_version` and repeated storage probes competing with the RTSP session after the viewer opened.

### v0.4.10-beta
- Changed: XIRO Xplorer Live View now follows the successful legacy post-`PLAY` ordering more closely by sending the recovered `3014 / 3012 / 3012 / 3014 / 3012 -> 3001 -> 3005 / 3006 -> 2016` sequence instead of jumping straight from RTSP startup to `3001 / 2016`.
- Changed: The XIRO Xplorer live-view startup path no longer front-loads camera clock sync before RTSP; clock sync is now part of the post-`PLAY` sequence, matching the successful legacy captures much more closely.
- Fixed: Fixed the newer `2026-04-23` mismatch where XIRO Lite had the right command families on the wire, but still differed from the legacy app in the exact live-view command ordering around `PLAY`.

### v0.4.11-beta
- Changed: Debug mode now adds dedicated `UDP First + Log` and `TCP First + Log` launchers in the Camera tab, so transport experiments can be repeated without manually reconfiguring Live View first.
- Changed: Entering Live View from those debug launchers now automatically starts a rooted 60-second combo capture that saves a `tcpdump` pcap, app-process logcat, and metadata file under `XIRO/live_view_logs/UDP` or `XIRO/live_view_logs/TCP`.
- Changed: Dedicated Live View now accepts an explicit startup stream profile from the Camera tab, making it possible to compare UDP-first and TCP-first behavior on the same rooted phone with matched logging.
- Changed: Viewer debug lines are now mirrored into Android logcat under `XiroViewer`, so the auto-captured logcat file includes the same in-view RTSP and recovery messages shown in XIRO Lite's debug overlay/export.
- Fixed: Rolled back the forced XIRO Wi-Fi route binding experiment so Live View is back on Android's default route behavior while RTSP transport debugging continues.

### v0.4.12-beta
- Changed: Restored a legacy-style 8 second RTP packet timeout across XIRO Lite Live View transport modes instead of the newer 30 second packet wait, matching the old XIRO stack more closely.
- Changed: UDP and TCP RTP data channels now surface packet-quiet time back to the loader instead of spinning invisibly inside the channel layer, so Live View can react sooner when no media ever arrives.
- Changed: The local Media3 RTSP fork now completes a dead UDP startup if no RTP packet arrives within the legacy-aligned startup window, allowing the built-in Media3 UDP-to-TCP retry to engage automatically instead of hanging on a black screen.
- Fixed: Reduced the regression introduced by over-long RTP packet waits, where XIRO Lite could sit black for too long before either transport fallback or UI recovery could kick in.
- Fixed: Brought XIRO Lite's transport startup behavior closer to the decompiled legacy stack's shorter socket-timeout / reconnect model without removing the newer legacy camera init and preview-kick work.

### v0.4.13-beta
- Fixed: Patched the local Media3 RTSP parser so fixed-length RTSP message bodies no longer require a trailing LF/CRLF.
- Fixed: XIRO Lite now accepts the camera's `GET_PARAMETER` response body `2013.07.03`, which the fresh rooted TCP and UDP captures proved is sent as a 10-byte body without any newline terminator.
- Fixed: Removed the repeated `IllegalArgumentException: Message body is empty or does not end with a LF` failure that was terminating RTSP receiver handling immediately after the first legacy keepalive reply in Live View.
- Fixed: Narrowed the current Live View blocker from "generic black screen" to transport validation after the RTSP parser mismatch, without discarding the newer legacy camera init, preview kick, and UDP-to-TCP fallback work.

## Outstanding / Found Issues
These are the items that are still open, experimental, or waiting on more capture data. Fixed issues should live under the version where they were corrected.
- Live View video still needs fresh hardware validation after the RTSP parser fix for XIRO's newline-free `GET_PARAMETER` body.
- Live View still needs field validation with the rooted `UDP First + Log` and `TCP First + Log` capture paths to confirm whether any remaining blocker is now packet delivery / decoder startup rather than RTSP control parsing.
- The local patched Media3 RTSP module should be rechecked whenever Media3 is upgraded from `1.5.1`.
- Remote video thumbnail and screennail transport is still not fully decoded, so remote camera videos may still fall back to placeholders or best-effort previews.
- Remote media info is still best-effort and may show `Unknown` when the camera does not return reliable file headers or stream metadata.
- Remote camera-SD delete is intentionally disabled until the exact legacy delete transport is proven safe.
- Live View camera mode is still inferred locally; the legacy current-mode callback exists, but its on-wire transport has not been decoded yet.
- FOV in the legacy app appears to be a camera-side field-of-view or digital zoom callback, not core flight telemetry.
- Xplorer 4K still needs real hardware validation to confirm live-view timing, camera command behavior, and Ambarella-side time sync.
- Freshly reset range extenders still need more hardware verification across firmware variants to confirm the full bind flow.
- Extender rename and extender password changes still use best-effort HTTP transport until the exact legacy transport split is fully proven.
- Current bound camera SSID may still remain unavailable on some relay firmware revisions until every current-air response variant is decoded.
- Remote battery percentage is calibrated from known captures but should remain experimental until more low-end remote battery captures are validated.
- Offline maps require users to import compatible `.map` region files before real geography appears.
- Broader whole-app `.hj` logging outside Live View may still need a dedicated recorder lifecycle later.
- UAV-time sync transport is intentionally excluded until the separate legacy flight-control path is proven on-wire.
- Deeper legacy warnings like return-home state and optical-flow fault are still excluded until raw fields are validated.

## Release Checklist
- Bump `versionCode` and `versionName` in `app/build.gradle.kts`
- Update the version header and version history in `DEV_NOTES_LOCAL.md`
- Update `currentReleaseNotes()` in `app/src/main/java/com/example/xirolite/MainActivity.kt`
- Verify `currentAboutInfo()` in `app/src/main/java/com/example/xirolite/MainActivity.kt` still reflects the correct project status, URLs, and About-page messaging
- Run `./gradlew.bat :app:compileDebugKotlin` before handing off project changes
- Only run an APK-producing Gradle task when an APK is explicitly requested
