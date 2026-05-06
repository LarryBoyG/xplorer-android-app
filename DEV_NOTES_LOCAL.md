# XIRO Lite Local Dev Notes

## Current Version: v0.4.86-beta

### App Version
- versionName: 0.4.86-beta
- versionCode: 186

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

### v0.4.86-beta
- Changed: `FF Native` is now the default non-debug live-view path, so the normal XIRO Lite camera launch uses the new native FFmpeg decoder route instead of the older experimental Flight Feed surface path.
- Changed: The older `Flight Feed` path is now debug-only, keeping it available for comparison and regression testing while making the smoother lower-latency native path the standard viewer experience.
- Updated: Disabling Debug Mode now automatically normalizes any saved debug-only player-path selection back to `FF Native`, which avoids hidden player-path state carrying over into the normal app flow.

### v0.4.85-beta
- Fixed: `FF Native` no longer crashes at player startup on Android 9 / Qualcomm devices because the locally built `libavcodec.so` now includes the missing H.264 film-grain cleanup object that previously left `ff_aom_uninit_film_grain_params` unresolved at runtime.
- Updated: The local FFmpeg Android build script now patches FFmpeg's `libavcodec/Makefile` so H.264 SEI builds pull in `aom_film_grain.o`, which closes the symbol hole without changing the rest of XIRO Lite's native decode path.
- Updated: This pass is a narrow runtime recovery build on top of the new native FFmpeg experiment, intended to get `FF Native` back to first picture so live-view smoothness and latency can be evaluated again from a working baseline.

### v0.4.84-beta
- Changed: The old `FF Soft` experiment has been replaced by a real native `FF Native` path that keeps XIRO Lite's custom Flight Feed RTP/RTSP session but decodes H.264 access units through a bundled FFmpeg software decoder and renders them into a software bitmap.
- Updated: XIRO Lite now builds and packages locally generated FFmpeg Android shared libraries (`libavcodec`, `libavutil`, `libswscale`) plus a dedicated JNI bridge `libxiro_ffmpeg.so` for `arm64-v8a` and `armeabi-v7a`.
- Updated: Open-source runtime notes now reflect that the app bundles both GStreamer and FFmpeg LGPL runtimes for the experimental native player paths, making this the first XIRO Lite build that mirrors the legacy app's native FFmpeg decode architecture instead of only tuning Android `MediaCodec`.

### v0.4.82-beta
- Changed: Flight Feed now runs a small legacy-style camera `cmd=3012` keepalive loop during active XIRO Xplore live view instead of relying only on the earlier one-shot post-PLAY command burst.
- Updated: Recent Lite vs Xplore capture comparison showed only moderate differences in IDR burst size and IDR:P ratio, but the legacy app appeared to lean more heavily on repeated `3012` camera keepalives during live view than XIRO Lite did.
- Updated: This pass is a camera-state alignment experiment rather than a decoder rewrite; the goal is to see whether holding the camera in a more Xplore-like live-view control cadence nudges the preview stream away from XIRO Lite's slightly peakier burst shape on Qualcomm.

### v0.4.83-beta
- Changed: Flight Feed `AUTO` decoder selection is now explicitly device-aware and legacy-like, with Qualcomm-class devices preferring standard OMX Qualcomm AVC decoders ahead of low-latency/C2 variants instead of using the previous generic order.
- Updated: Native Ghidra analysis of legacy `libVideoStream.so` confirmed that XIRO Xplore has its own RTP-to-H.264 assembly plus an FFmpeg software decode path behind a simple hardware/software switch, so this pass mirrors the most concrete policy clue we could copy today: legacy-era Qualcomm preference ordering.
- Updated: Debug logs now print the detected AUTO device policy and the Build fingerprint fields used to choose it, so follow-up captures can show exactly which legacy-like branch XIRO Lite took on each phone.

### v0.4.81-beta
- Changed: Flight Feed now delays decoder startup until the first decodable live sample is queued, instead of creating and starting the Qualcomm AVC decoder immediately from SDP alone right after RTSP `SETUP`.
- Changed: When live in-band `SPS/PPS` arrives before startup, Flight Feed now uses those parameter sets and their parsed resolution to configure the decoder, which is much closer to the real XIRO Xplore `ZdCodecImpl` startup order seen in the latest legacy decoder log.
- Updated: If the stream does not deliver live parameter sets quickly enough, Flight Feed falls back to SDP-based decoder init after a short wait so startup does not hang indefinitely on edge cases.

### v0.4.80-beta
- Fixed: The first GStreamer `h264timestamper` cadence repair pass caused black-screen startup failures because the XIRO camera still negotiates parsed H.264 caps with `framerate=0/1`, and hardcoding `30/1` at the caps filter made the pipeline fail with `not-negotiated (-4)` before first frame.
- Changed: The GStreamer pipeline now keeps AU-aligned H.264 going into `h264timestamper` but stops forcing an explicit frame rate there, which restores permissive caps negotiation while preserving the monotonic timestamp repair stage itself.
- Updated: This is a narrow recovery build intended to get GStreamer picture back first; once video is stable again, cadence/latency tuning can continue from a working baseline instead of a broken one.

### v0.4.62-beta
- Changed: Debug Mode now exposes a `Flight Feed FPS` toggle on the Camera tab whenever the custom `Flight Feed` path is selected, so close-range comparisons can switch the experimental local presentation clock between `30 fps` and `15 fps` before entering Live View.
- Changed: The dedicated viewer now passes that frame-rate mode through its launch intent and applies it end-to-end inside the custom `Flight Feed` session, including the local presentation clock and the decoder's frame-rate / operating-rate hints.
- Updated: Rooted `Flight Feed` logs now include the selected target FPS in both the decoder configuration line and the recurring metrics output, making it straightforward to confirm which cadence a given capture actually used.

### v0.4.63-beta
- Changed: The failed `Flight Feed FPS` experiment has been removed again, and `Flight Feed` is now the default normal live-view path while `Media3` is only exposed through Debug Mode as a fallback/testing path.
- Changed: The custom `Flight Feed` decoder loop now uses separate input and output workers with small blocking waits, which is much closer to the legacy app's queue/dequeue thread shape than the previous single combined coroutine loop.
- Updated: The hardware decoder configuration is now simpler and more legacy-like again, keeping the explicit 30 fps target but removing the newer `LOW_LATENCY` and `OPERATING_RATE` hints that may have been contributing to hitchy cadence on the custom path.

### v0.4.64-beta
- Changed: `Flight Feed` now releases decoded hardware frames against a tiny explicit local render clock instead of dumping them onto the surface as fast as the decoder happens to produce them.
- Changed: The render clock is intentionally shallow, allowing at most about one frame of lead and resetting immediately if the decoder stalls, so the goal is smoother presentation without sliding back toward Media3-like delay.
- Updated: This pass targets the remaining hitch where rooted logs showed healthy RTP, no queue buildup, and no sample drops, which points much more toward decoder output cadence than transport instability.

### v0.4.65-beta
- Fixed: The first local render-clock pass could clamp multiple decoder-output bursts onto the same near-future surface timestamp, which risks turning healthy decoded output into visible micro-freezes and jumps.
- Changed: `Flight Feed` now schedules hardware-frame release from each sample's own presentation timeline instead of a shared burst timestamp, while still keeping the render lead shallow enough to avoid sliding back toward Media3-like latency.
- Updated: The render lead has been relaxed slightly to a few frames so the hardware decoder can spread out bursty output more naturally without collapsing everything onto one vsync window.

### v0.4.66-beta
- Fixed: Recent rooted Flight Feed runs were still stuffing about 10-15 frames into the hardware decoder even while the sample queue stayed empty, which is a strong sign of hidden decoder-side backlog rather than wire starvation.
- Changed: After the first frame is on screen, Flight Feed now caps its steady-state decoder in-flight depth to a shallow few frames instead of continuously feeding the codec as fast as samples arrive.
- Updated: Startup remains unrestricted so first picture should stay reliable, but steady playback now aims to behave more like a current live feed than a half-second buffered decoder queue.

### v0.4.67-beta
- Fixed: The steady-state in-flight decoder cap introduced in `0.4.66-beta` was too aggressive for this device and caused Flight Feed to freeze right after first frame while stale queued samples piled up behind it.
- Changed: Flight Feed is back to its prior unrestricted decoder feed behavior so Live View returns to the last known working custom-path state instead of stalling after startup.
- Updated: The latest rooted capture still produced one useful clue despite the regression: after first frame, the sample queue filled and aged out while the decoder stayed pinned at the cap, confirming that the added gate itself was the cause of the new freeze.

### v0.4.68-beta
- Changed: Flight Feed now presents through a `TextureView`/`SurfaceTexture` path again instead of the newer direct `SurfaceView` output surface, while keeping the rest of the custom RTP/H.264 pipeline intact.
- Updated: This lines XIRO Lite back up more closely with the decompiled legacy camera stack, where the live camera fragment points at `ZDVideoView` and the codec path appears to work through a texture-backed render surface rather than a simple direct surface handoff.
- Updated: The latest cross-device logcats suggest the remaining hitch is most likely tied to the Qualcomm decoder/output presentation path, since the same Flight Feed pipeline is smooth on the Tensor Pixel but not on the Qualcomm phone.

### v0.4.69-beta
- Changed: Flight Feed now prefers Qualcomm low-latency AVC decoder variants ahead of the standard Qualcomm hardware component whenever those variants are exposed by the device codec list.
- Updated: The new Pixel / Qualcomm / legacy logcats showed that legacy is still targeting `640x360` at `30 fps`, while XIRO Lite on the Qualcomm phone is already healthy on the wire and only diverges in the decoder/output path.
- Updated: Legacy also logs a real queue-overflow recovery path (`queue is exceeded` -> reconnect), which suggests the remaining close-range stutter is not coming from low target FPS or missing transport-side recovery, but from how the Qualcomm decode/presentation stack behaves once frames are already arriving cleanly.

### v0.4.70-beta
- Changed: Debug Mode now exposes a `Flight Feed Decoder` selector on the Camera tab, so the Qualcomm phone can be tested directly against `Auto`, `Qualcomm standard`, `Qualcomm low-latency`, and `Software only` AVC decoder strategies before Live View starts.
- Updated: The viewer intent now carries the selected Flight Feed decoder strategy into the dedicated camera screen, and rooted debug logs record both the requested decoder name and the actual codec MediaCodec configures.
- Updated: This pass is focused on isolating the remaining Qualcomm-only hitch now that cross-device logs show the custom RTP/H.264 pipeline itself is smooth on the Tensor Pixel and that legacy still runs `640x360` at `30 fps`.

### v0.4.71-beta
- Changed: Debug Mode now exposes a second experimental `Flight Feed GL` player path that keeps the current custom RTP/H.264 session but swaps the final presentation step to an offscreen `SurfaceTexture` plus a tiny local GLES renderer.
- Changed: Both `Flight Feed` variants still share the same decoder-strategy selector, so Qualcomm testing can compare the direct codec-to-surface path against the new GL-presented path without changing the wire/session logic underneath.
- Updated: This pass is focused squarely on the remaining Qualcomm-only hitch after earlier cross-device tests showed the same `Flight Feed` session logic is smooth on the Tensor Pixel but not on the Qualcomm phone.

### v0.4.72-beta
- Changed: Debug Mode now exposes a third experimental `Flight Feed Soft` player path that keeps the current custom RTP/H.264 session but forces software AVC decode in manual buffer mode instead of feeding a codec surface directly.
- Changed: The new software path converts decoded YUV frames into a locally rendered bitmap view, which is the first XIRO Lite experiment aimed at bypassing both the Qualcomm hardware decode cadence issue and the hidden surface-queue delay seen in the normal software-decoder path.
- Updated: This is the first concrete step into the heavier “option 3” work: a custom-rendered software-decode pipeline that reuses the proven Flight Feed transport/session logic rather than bolting the app back onto Media3.

### v0.4.73-beta
- Changed: `Flight Feed Soft` now prioritizes currentness over completeness by dropping decoded software-output frames once they lag more than a few frames behind the newest queued sample.
- Changed: Rooted Flight Feed metrics now include `outputDrops`, making it much easier to see whether the software path is catching up by discarding stale decoded output instead of quietly acting like a buffered player.
- Updated: This pass is focused directly on the user's flight-safety priorities: no one-second freezing, no creeping delay, and acceptable lower framerate if that is the cost of staying current.

### v0.4.74-beta
- Fixed: The `0.4.73-beta` `Flight Feed Soft` stale-output-drop experiment could black-screen the viewer because software decode on this device was already running roughly half a second behind, which caused nearly every decoded frame to be discarded as stale.
- Fixed: Viewer debug logging from background Flight Feed workers could crash the app with a Compose `SnapshotStateList` mutation error; log-list updates are now marshalled back onto the main thread before touching UI state.
- Changed: `Flight Feed Soft` is back to its prior visible-but-delayed baseline while we evaluate heavier low-latency alternatives such as a dedicated GStreamer pipeline instead of stacking more failing output-drop experiments onto the software path.

### v0.4.75-beta
- Changed: The app's About/open-source model now has dedicated placeholders for a bundled media runtime, so future runtime attribution and license notices can sit beside the existing map attribution instead of being added ad hoc later.
- Updated: Added local GStreamer integration notes covering Android bundling, JNI/runtime shape, and the licensing/attribution checklist before a real experimental GStreamer path is wired into XIRO Lite.
- Updated: The current release focus is preparation and disclosure plumbing for a heavier GStreamer experiment, while keeping the restored `0.4.74-beta` live-view baseline stable.

### v0.4.76-beta
- Changed: XIRO Lite now bundles a real debug-only native GStreamer Android runtime path, including the official Android helper runtime, JNI bridge, CMake/NDK integration, and a Compose-hosted surface entry point.
- Changed: The experimental `GStreamer` player path now builds and packages native `libgstreamer_android.so` and `libxiro_gstreamer.so` for all target ABIs, instead of stopping at documentation or placeholder plumbing.
- Updated: Local licensing/runtime disclosure now includes bundled GStreamer LGPL notices and runtime attribution so the native media stack is documented alongside the rest of the app's third-party components.

### v0.4.77-beta
- Fixed: The Camera tab debug hero card now expands with its content again, so the added pipeline/decoder controls no longer push the rooted logging launchers off the bottom of smaller phone screens.
- Fixed: The `Player Path` and `Flight Feed Decoder` chip rows now wrap cleanly instead of clipping horizontally once multiple experimental paths are available in Debug Mode.
- Updated: This pass is purely a debug-UX cleanup on top of the real bundled GStreamer runtime work, making the new live-view experiments reachable again without hiding the existing logging buttons.

### v0.4.78-beta
- Changed: The experimental GStreamer live-view path now inserts `h264timestamper` after H.264 parsing so presentation timestamps are rebuilt before frames hit the Qualcomm decoder.
- Changed: The native mobile runtime now explicitly bundles the `codectimestamper` plugin family in addition to the existing parser, RTSP, Android media, and OpenGL components.
- Updated: This pass is aimed directly at the once-per-second freeze that still survived both GStreamer UDP and GStreamer TCP while the Qualcomm decoder logged decreasing H.264 timestamps.

### v0.4.79-beta
- Changed: The GStreamer timestamp-repair path now provides an explicit `framerate=30/1` H.264 caps hint before `h264timestamper`, instead of letting the repair element assume `25 fps`.
- Updated: The latest UDP and TCP GStreamer captures showed the earlier decreasing-timestamp warning disappear, but both runs still logged `Unknown frame rate, assume 25/1`, which likely explains the new added delay.
- Updated: This pass is intentionally surgical again so the next test tells us whether the remaining extra lag is just the wrong timestamp-repair cadence or something deeper in the Qualcomm decode path.

### v0.4.47-beta
- Changed: The Camera tab now exposes a persistent `Player Path` toggle, so XIRO Lite can launch Live View through either the existing `Media3` player or a new experimental `Flight Feed` path before entering the viewer.
- Changed: `Flight Feed` is a first-pass custom legacy-style UDP pipeline that performs RTSP control directly, sends the inferred XIRO UDP punch, maintains a tiny H.264 queue, and aggressively drops stale queued video instead of behaving like a generic buffered media player.
- Updated: The dedicated viewer now passes the selected player-path mode through its launch intent, so field testing can compare both pipelines against the same camera, telemetry, and recovery logic without changing the rest of the app.

### v0.4.57-beta
- Changed: The dedicated viewer no longer forces a whole-screen telemetry age refresh every second during Live View; it now only updates when telemetry actually crosses the stale or lost thresholds.
- Changed: This keeps the safety/status transitions intact while removing a rhythmic `1 Hz` Compose churn source from the flight viewer path.
- Fixed: Legacy comparison suggested XIRO Lite was still doing more live-session bookkeeping than the original app, and the once-per-second viewer refresh was one of the most suspicious remaining telemetry-side differences.

### v0.4.58-beta
- Changed: `Flight Feed` now chooses its AVC decoder through an explicit candidate list instead of relying on Android's default `createDecoderByType()` selection, keeping the custom low-latency UDP pipeline while aligning codec choice more closely with the smoother Media3 path.
- Changed: The custom path now prefers software and non-hardware AVC decoders first, with hardware fallback still available if the preferred candidates cannot be created on the device.
- Updated: Rooted debug logs now print the `Flight Feed` decoder candidates and the exact codec name that was actually configured, so the next comparison run can confirm whether the remaining skip is tied to decoder selection or something deeper in the custom pipeline.

### v0.4.59-beta
- Changed: `Flight Feed` now moves back toward hardware AVC decoding for low-latency live view, but keeps an explicit tiny in-flight sample cushion so the custom path has a little smoothing headroom instead of exposing every arrival wobble directly.
- Changed: The custom metrics line now reports `inflight=` alongside the existing queue and RTP counters, making it easier to see whether the live feed is holding the intended 2-4 frame cushion or collapsing back to zero.
- Fixed: The `0.4.58-beta` software-decoder pass stopped the visible hitching, but it also brought back the same significant delay as Media3, which strongly suggested we needed a shallow explicit buffer on the low-latency path rather than the software decoder's hidden deeper buffering.

### v0.4.60-beta
- Fixed: The first hardware-cushion `Flight Feed` pass could deadlock before first video because it capped in-flight samples too early, leaving the decoder stuck at a tiny startup queue that never produced an initial frame while new samples were dropped behind it.
- Changed: `Flight Feed` now allows a wider startup in-flight window until the first frame is actually rendered, and only then tightens back down to the intended shallow 2-4 frame live cushion.
- Updated: The post-startup low-latency hardware-buffer experiment remains in place, but the startup path should now at least produce visible video again so we can judge whether the tiny explicit cushion is helping or hurting once frames are on screen.

### v0.4.61-beta
- Fixed: The custom `Flight Feed` path still did not tolerate the explicit in-flight buffer experiment well, so the decoder-side queue gating has been removed again to get the legacy-style UDP feed back out of the black-screen state.
- Changed: The safer explicit decoder-selection work remains in place, including rooted logging of the candidate AVC decoders and the exact codec that actually gets configured.
- Updated: This rollback keeps the newer hardware/software decoder visibility while backing away from the sample-buffer experiment that was turning the feed into repeated startup failures.

### v0.4.56-beta
- Changed: Live View HJ recording is now buffered and asynchronous, so `.hj` packets are queued onto an IO writer instead of forcing a file `write()+flush()` in the hot viewer path for every UDP telemetry packet.
- Changed: The dedicated viewer no longer rewrites its `hjLogStatus` Compose state on every live UDP packet, which avoids extra whole-screen state churn while Flight Feed is trying to stay smooth and current.
- Fixed: XIRO Lite was doing more live-view bookkeeping than the legacy app appears to do, and the per-packet `.hj` file write path was one of the strongest remaining telemetry-side candidates for periodic hitching.

### v0.4.55-beta
- Changed: `Flight Feed` no longer posts a main-thread callback on every rendered frame, which removes a steady stream of per-frame Compose/UI churn from the custom low-latency path.
- Changed: The custom decoder loop now behaves more like a shallow dedicated queue/dequeue pipeline by draining output and feeding several pending samples per pass instead of alternating through a single poll/sleep cycle.
- Fixed: Recent rooted captures showed healthy RTP and roughly 30 decoded samples per second even while the picture still skipped, pointing at our own decode-delivery rhythm rather than packet loss.

### v0.4.48-beta
- Fixed: The first rooted `Flight Feed` capture showed the custom path tearing itself down almost immediately after `PLAY`, so the session now stays alive for the full Live View lifetime instead of returning and cleaning up its sockets and decoder right away.
- Fixed: The custom pipeline should no longer black-screen after only a couple of RTP packets because its RTSP, RTP, heartbeat, and decoder loops are now held open until Live View actually exits.
- Changed: `Flight Feed` remains experimental, but this build is the first one where the custom path should genuinely stay up long enough to test its legacy-style tiny-queue behavior against the existing Media3 path.

### v0.4.49-beta
- Fixed: The first successful `Flight Feed` capture still showed transient startup ENETUNREACH failures before the phone fully settled onto the XIRO Wi-Fi route, so the custom path now retries RTSP control connection briefly instead of bailing immediately on that window.
- Fixed: Intentional viewer exit and socket shutdown are now treated as expected inside `Flight Feed`, which cleans up the noisy RTP/decoder recovery spam that was still appearing at teardown in the rooted logs.
- Updated: `Flight Feed` queue-drop metrics now count real stale-sample drops, so the debug log reflects actual frame shedding while we compare the custom path against legacy behavior in the field.

### v0.4.50-beta
- Changed: `Flight Feed` now treats small RTP packet gaps as a damaged-frame event instead of a decoder emergency, so mild loss drops the bad access unit but keeps the current decode path alive instead of flushing MediaCodec and waiting for a full reset.
- Changed: Hard recovery is now reserved for larger loss bursts, active keyframe damage, or sessions that were already waiting on a clean recovery frame, which is much closer to the “keep showing something current” behavior seen from the legacy app in the field.
- Updated: The experimental custom queue is slightly wider but trims stale frames sooner, giving the decoder a little more breathing room to smooth cadence without turning the flight feed into a laggy buffer.

### v0.4.51-beta
- Changed: `Flight Feed` now briefly holds a one-packet RTP skip before declaring loss, so tiny out-of-order delivery has a chance to settle instead of being treated as immediate frame damage.
- Changed: When the missing packet does arrive right behind the held packet, the custom pipeline now feeds both packets in order and continues decoding without dropping the frame or forcing recovery.
- Fixed: Nearby “jittery even with strong signal” behavior pointed to over-eager loss detection rather than true RF weakness, so this pass specifically targets false packet-gap reactions inside the experimental legacy-style pipeline.

### v0.4.54-beta
- Changed: `Flight Feed` no longer trusts the XIRO camera's RTP timestamps for MediaCodec presentation pacing; it now feeds the decoder on a steady local 30 fps clock that better matches the legacy app's shallow live-feed behavior.
- Fixed: Combined XIRO Lite/XIRO Xplore stopwatch captures showed that the camera's RTP timestamps appear non-monotonic or low-bit wrapped, which can create a periodic freeze/jump cadence when treated as normal presentation timestamps.
- Updated: The earlier gap-recovery smoothing from `0.4.53-beta` remains in place underneath this pacing fix, so the custom path should now be both less freeze-prone after loss and less likely to stutter even at close range.

### v0.4.53-beta
- Changed: `Flight Feed` is now much less eager to switch into `waiting for the next key frame` after small RTP gaps, especially when the damage happens inside a current keyframe access unit.
- Changed: The one-packet reorder hold now applies inside keyframe access units too, and the custom queue is slightly more forgiving before stale samples are trimmed.
- Fixed: Strong-signal sessions that looked like a repeating one-second freeze/unfreeze pattern were pointing to over-aggressive keyframe-wait recovery rather than raw FPS limits, so this pass targets that cadence directly.

### v0.4.52-beta
- Changed: `Flight Feed` now renders through a dedicated `SurfaceView` path instead of the old `TextureView`, which is a closer match to the legacy app’s surface-based video presentation and avoids an extra compositor hop on older phones.
- Changed: The experimental decoder is now configured with an explicit 30 fps / 30 operating-rate hint so the custom path describes its intended cadence directly instead of leaving render pacing entirely implicit.
- Fixed: Recent rooted captures showed `Flight Feed` decoding plenty of frames with no queue backlog or frame drops while still looking visibly jittery, which pointed to render-path smoothness rather than network starvation or H.264 throughput.

### v0.4.46-beta
- Changed: XIRO Lite no longer auto-rebuilds a live UDP session just because buffered-ahead latency crosses the unset-live-offset threshold while frames are still moving; backlog is now left to playback-speed catch-up unless the stream is actually dead.
- Changed: Latency threshold crossings with `liveOffset=unset` are still logged in Debug Mode, but they no longer trigger the same hard RTSP restart path that had been trying to "fix" lag in flight.
- Fixed: Fresh April 30 rooted flight captures showed XIRO Lite repeatedly freezing the moving feed by rebuilding the stream on latency alone, even though RTP was still flowing and the legacy app would have kept showing a degraded but current picture instead.

### v0.4.45-beta
- Changed: The experimental direct live-edge queue trim from `v0.4.44-beta` has been removed, so XIRO Lite no longer tries to jump forward inside a still-healthy UDP session just because buffered-ahead time crosses the backlog threshold.
- Changed: The earlier aggressive catch-up, UDP-first rebuild, and low-latency buffer tuning remain in place, but backlog reduction is back to speed-based trimming and controlled recovery rather than in-session direct seeks.
- Fixed: Fresh rooted April 30 captures showed every `Live queue trim` firing an RTSP `PAUSE`, which pushed the feed into long `BUFFERING` freezes even while RTP packets were still arriving, so this release rolls that experiment back cleanly.

### v0.4.36-beta
- Changed: Relay signal text can now come from the repeater-status payload itself, so XIRO Lite no longer depends only on the separate current-air probe to show an active relay-to-camera link.
- Changed: Live-view snapback now works even when Media3 leaves `currentLiveOffset` unset, by seeking directly toward the buffered live edge instead of waiting for a dynamic live-offset value that never arrives.
- Fixed: Telemetry Status no longer marks `Camera: Connected` just because the phone is on the repeater Wi-Fi; it now requires direct camera Wi-Fi, successful camera HTTP traffic, or an actually active relay link.
- Fixed: When the extender is clearly connected but no numeric relay signal has been surfaced yet, the Wi-Fi status now reports `Link active` instead of the misleading `Waiting for relay link`.

### v0.4.37-beta
- Changed: The live snapback guard no longer waits on the older rendered-frame gate, so a growing buffered-ahead backlog can be corrected even when Media3 still leaves the stream in the `liveOffset=unset` state.
- Changed: When no live offset is available, XIRO Lite now uses a more realistic buffered-ahead snapback threshold and a faster cooldown that is better suited to weak-signal flight behavior than the previous slower retry window.
- Fixed: New rooted live-view logs showed XIRO Lite drifting to roughly 2.5-3.0 seconds behind after burst gaps while still reporting `snapbacks=0`, so this release targets that missed correction path directly.

### v0.4.38-beta
- Changed: When `liveOffset` remains unset, latency correction now prefers a fast UDP live-feed rebuild instead of a direct seek into the buffered edge, because recent rooted captures showed the seek path dropping XIRO Lite into long buffering while RTP packets were still flowing.
- Changed: Automatic TCP fallback is now reserved for true startup/no-first-frame recovery instead of later weak-signal buffering, so a real UDP video session no longer gets downgraded into the known-bad TCP transport just because the old first-frame flag never flipped.
- Fixed: New rooted captures showed XIRO Lite finally detecting backlog drift, but then either buffering for 20-30 seconds or falling over into TCP while control and RTP traffic were still alive, so this release changes that correction path to stay UDP-first.

### v0.4.39-beta
- Changed: Legacy UDP live view now uses a tighter low-latency buffer profile that prioritizes time over size thresholds, so XIRO Lite stops carrying as much steady-state queue even when RTP is healthy.
- Changed: When Media3 still leaves `currentLiveOffset` unset, XIRO Lite now applies a gentle manual playback-speed catch-up ladder based on buffered-ahead time instead of waiting only for an emergency rebuild threshold.
- Fixed: Fresh rooted April 30 captures showed XIRO Lite sitting at roughly `1.1-1.2s` behind even in otherwise healthy playback, so this release targets that remaining baseline latency gap directly.

### v0.4.40-beta
- Changed: The legacy UDP catch-up ladder is now more aggressive at moderate backlog levels, so XIRO Lite starts trimming delay earlier instead of waiting until the queue has already crept close to a full second.
- Changed: Momentary READY/BUFFERING blips no longer immediately reset live catch-up speed back to `1.00x`, which was letting XIRO Lite give away latency again during otherwise healthy weak-signal playback.
- Fixed: Fresh April 30 comparisons showed XIRO Lite settling around `600-700 ms` buffered-ahead while legacy stayed visibly tighter, with repeated tiny buffering blips interrupting the new catch-up logic before it could fully pay down that cushion.

### v0.4.41-beta
- Changed: Live-view auto-recovery now uses a small bottom-right spinner badge instead of a center-screen feed-stall popup, so rebuilds no longer sit over the middle of the camera view while flying.
- Changed: The Live View debug actions for `Export Debug Log` and `Show/Hide Debug Panel` now live inside the gear settings panel beside the transport tools instead of floating beside the feed.
- Fixed: The old rebuild-status popup and top-right debug buttons were taking up too much visual space during flight testing, especially while comparing latency behavior against XIRO Xplore in the field.

### v0.4.42-beta
- Changed: The legacy UDP path now uses an even tighter low-latency buffer profile and a more aggressive live catch-up ladder, so XIRO Lite starts paying down delay earlier and pushes harder when buffered video begins to creep upward.
- Changed: When live offsets remain unset, the fallback snapback threshold now trips sooner, which better matches the “current feed over pretty feed” goal during flight testing.
- Fixed: Fresh April 30 rooted XIRO Lite logs still showed RTP staying healthy while the app carried a mostly self-inflicted 600–700 ms queue at only 1.10x catch-up, so this release targets that remaining player-side latency cushion directly.

### v0.4.43-beta
- Changed: The legacy UDP live-view path now uses an intentionally aggressive low-latency buffer budget and a steeper catch-up ladder, prioritizing staying current over preserving a prettier delayed picture.
- Changed: The fallback snapback threshold and cooldown were tightened again, so XIRO Lite is quicker to shed backlog when the feed starts to drift behind the aircraft.
- Fixed: The latest rooted April 30 comparison still showed XIRO Lite voluntarily carrying around 600–700 ms of queue while RTP remained healthy, so this release pushes harder at that remaining self-imposed cushion.

### v0.4.44-beta
- Changed: When RTP is still healthy but XIRO Lite is carrying roughly 700–800 ms or more of buffered video, the player now tries a direct jump toward the buffered live edge instead of only relying on playback-speed catch-up.
- Changed: This new queue-trim path only engages when the relay stream is still actively arriving, so it is intentionally biased toward reducing self-imposed lag rather than rebuilding a still-healthy feed.
- Fixed: Fresh April 30 rooted captures showed XIRO Lite becoming more stable but still settling into a steady half-second-plus backlog, so this release targets that specific “stable but late” behavior directly.

### v0.4.32-beta
- Changed: XIRO Lite now treats the legacy UDP live feed as a latency-first path, with a smaller live target offset, no intentional slowdown below real time, and more aggressive catch-up speed when the stream starts to drift.
- Changed: When the live offset grows past roughly 1.2 seconds on the working UDP path, the player now snaps itself back toward the live edge instead of continuing to coast several seconds behind the aircraft.
- Fixed: Fresh weak-signal walk-away captures showed XIRO Lite continuing to receive sustained RTP while legacy stayed much closer to real time, so this release focuses on presentation latency rather than picture prettiness and favors a current moving image over a delayed clean one.

### v0.4.33-beta
- Changed: The latency-first UDP live-view path is now even more aggressive about staying current, with a smaller active buffer budget, a 100 ms target live offset, and stronger catch-up speed when playback begins to trail the aircraft.
- Changed: XIRO Lite's live snapback logic now uses both the player-reported live offset and the amount of decoded video already queued ahead, so it can skip back toward the live edge even when the RTSP source is still feeding a moving backlog.
- Fixed: A back-to-back same-phone capture with XIRO Xplore first and XIRO Lite second showed XIRO Lite still receiving sustained RTP while tolerating much larger burst gaps and letting playback drift 15+ seconds behind, so this release explicitly favors dropping behind-data over preserving a delayed feed.

### v0.4.34-beta
- Changed: Debug Mode now extends the existing Live View debug log with recurring current-live-offset, buffered-ahead, snapback-count, and RTP burst-gap metrics every few seconds during an active stream.
- Changed: The RTSP module now counts real RTP packet arrivals on both UDP and TCP transport paths, so exported viewer logs and rooted combo captures can be compared without opening Wireshark first.
- Fixed: Weak-signal tuning no longer has to rely on indirect symptoms alone, because the active XIRO Lite viewer now records how often packet bursts stall for 100/250/500/1000 ms and whether playback snapbacks are keeping up with those gaps.

### v0.4.35-beta
- Changed: Rooted debug combo capture is now session-based instead of fixed at 60 seconds, so UDP/TCP debug launches keep logging for the full Live View session and stop when the viewer exits.
- Changed: The debug launcher hint text and capture metadata now reflect the new session-length behavior instead of advertising a hard 60-second window.
- Fixed: Long live-view investigations no longer silently stop rooted pcap/logcat capture mid-flight just because the previous fixed timer expired.

### v0.4.31-beta
- Changed: XIRO Lite's RTSP media source now advertises live XIRO sessions as dynamic timelines instead of fixed ones, which is a better fit for a moving live edge during flight.
- Changed: The low-latency live configuration added in v0.4.30-beta can now act on a true dynamic live timeline instead of being attached to a source that still looked static to the player core.
- Fixed: New range-walk captures showed XIRO Lite receiving a healthy amount of RTP while still drifting seconds behind real time, which pointed to playback presentation and live-edge tracking rather than missing media alone.

### v0.4.30-beta
- Changed: Live View now uses a lower-latency RTSP buffer profile on the active UDP path and advertises a tighter live target offset, so XIRO Lite is less likely to drift multiple seconds behind the aircraft under weak-signal flight tests.
- Changed: The H.264 RTP reader now distinguishes between losing packets inside the frame that is currently being assembled versus skipping a whole completed access unit between RTP markers.
- Fixed: April 29 range-walk captures showed XIRO Lite freezing for seconds where XIRO Xplore kept showing weak-signal artifacts, which pointed to XIRO Lite waiting for a perfect replacement keyframe too aggressively after non-key packet loss.
- Fixed: Fragmented non-IDR frame loss now drops only the damaged frame and keeps live playback moving, while still preserving strict keyframe recovery when an IDR or in-flight keyframe path is actually damaged.

### v0.4.29-beta
- Changed: Active Live View now snapshots extender status and camera SD storage once when the session starts instead of continuing to poll those management endpoints every few seconds while flying.
- Changed: The dedicated viewer no longer sends the extra HTTP `cmd=3012` live-view keepalive loop during active XIRO Xplorer playback, relying on the RTSP session keepalive instead.
- Fixed: Flight captures showed real UDP 6800 telemetry gaps and repeated RTSP rebuilds while XIRO Lite was still running repeater and camera management traffic in the background, so this release removes that avoidable live-flight chatter from the active stream path.

### v0.4.22-beta
- Changed: Live View now logs the full ordered list of available H.264 decoders and the exact decoder that Media3 actually initializes on-device, so future distortion captures can be tied to a real codec path instead of guesswork.
- Changed: The XIRO H.264 RTP reader now treats aggregated STAP-A packets as real keyframe candidates, detects embedded SPS/PPS/IDR units, and flags those access units as keyframes instead of always labeling them non-key.
- Fixed: After RTP packet loss, the reader now distinguishes between loss inside an in-flight access unit versus loss between access-unit boundaries, dropping the damaged unit cleanly while still forcing a wait for the next keyframe.
- Fixed: The new flashing-between-distortion-patterns clue strongly pointed to repeated keyframe/resync trouble, so this release hardens the exact SPS/PPS/IDR recovery path rather than only tweaking RTSP transport timing.

### v0.4.25-beta
- Changed: The extender bind page now shows an active loading spinner while XIRO Lite is searching for available access points, instead of dropping straight to a static empty-state message during a refresh.
- Changed: Selecting a bind candidate now replaces the old `Selected` badge with an in-row spinner and `Binding...` label while XIRO Lite is actively attempting the bind, which better matches the legacy app's temporary reconnect behavior.
- Fixed: Bind-page refreshes, auto-loads, and post-bind rescans now track their own scan-in-progress state, so the UI reads as "working/reconnecting" instead of looking frozen while the extender briefly drops Wi-Fi during a successful bind.

### v0.4.26-beta
- Changed: XIRO Lite now treats a successful bind command as a reconnect phase instead of immediately forcing a relay rescan that can only fail while the extender intentionally drops Wi-Fi.
- Changed: The last successfully bound camera SSID is now persisted locally and reused as a fallback in Settings and the bind page until the extender reports the live bound-network value back cleanly.
- Fixed: `Failed to connect to /192.168.2.254:80` is no longer allowed to appear as a fake Wi-Fi candidate during bind-menu refreshes.
- Fixed: Settings no longer drops the newly bound camera back to `Unavailable` just because the extender is mid-reconnect after a successful bind request.

### v0.4.27-beta
- Changed: Shared XIRO button styling now fills its full pill surface cleanly, removing the stray gray backing bars that were still showing behind several Settings and bind-flow buttons.
- Changed: The Live Camera Settings dialog is now wider and centered more naturally in landscape, so the four legacy tabs fit without sideways tab scrolling.
- Changed: The Live Camera Settings header now uses the same back-arrow language as the rest of XIRO Lite instead of a separate Close button and explanatory helper paragraph.
- Changed: Verified and observed camera controls now speak for themselves visually, while only still-pending rows keep the `Command pending` badge.

### v0.4.28-beta
- Fixed: The first-launch disclaimer now shows a real `Agree and Continue` button again instead of leaving a blank tappable strip on the right side of the dialog.
- Changed: Release Notes now describe only the current XIRO Lite version and include a direct link to the full GitHub releases changelog.
- Changed: Live Camera Settings command-finding notes are now hidden unless Debug Mode is enabled, keeping the normal flight UI focused on the controls themselves.
- Changed: The active Image Resolution choice now highlights in XIRO green, matching the selected-state treatment used by the verified camera controls.

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
