# Guitar FX

Android electric-guitar effects processor designed for a first-generation 3.5 mm iRig interface.

## Repository status
This repository was empty at initial audit (no branches, commits, or source files). The initial implementation will therefore establish a minimal native Android foundation rather than preserve a pre-existing app structure.

## Audio compatibility notes
- The original 3.5 mm iRig is an analogue headset interface. Android support depends on the phone's headset jack, TRRS wiring, audio policy, and headset microphone routing.
- Wired monitoring is the preferred low-latency path. Bluetooth output may introduce substantial latency and is not suitable for tight live playing on many devices.
- The app must verify actual input/output routing on the target phone; UI status alone is not proof that audio is flowing.

## Planned milestones
1. Establish a buildable Android app and CI.
2. Verify live audio input-to-output passthrough.
3. Add a real-time DSP chain (gate, drive, amp/EQ, modulation, delay, reverb).
4. Add presets and route/latency diagnostics.

No claim of verified APK build or physical iRig testing is made until CI and device tests pass.
