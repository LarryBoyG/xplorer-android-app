# Offline Maps

XIRO Lite includes support for open source offline map rendering for Live View and Past Flights replay.

## Map Engine

Offline maps in XIRO Lite are rendered using the open source [mapsforge](https://github.com/mapsforge/mapsforge) project.

mapsforge is used only for offline map display inside the app. XIRO Lite does not rely on Google Maps or other paid map SDKs for this feature.

## Map Data

XIRO Lite is designed to work with offline `.map` files in the mapsforge format.

Those map files are commonly derived from [OpenStreetMap](https://www.openstreetmap.org/) data.

OpenStreetMap data is made available under the [Open Database License (ODbL)](https://www.openstreetmap.org/copyright).

Required attribution:

`Copyright OpenStreetMap contributors`

## Project Intent

The offline map feature exists to support flight visualization, replay, and compatibility research for legacy XIRO hardware while keeping the mapping stack open, transparent, and legally usable.

XIRO Lite does not claim ownership of third-party map data, map files, trademarks, or related branding.

## Notes

- XIRO Lite uses offline maps only.
- Users may need to download or import compatible mapsforge `.map` files separately.
- Public online OpenStreetMap tile servers are not used as the app's runtime map backend for this feature.
- This file exists so GitHub releases and repository viewers have a clear record of the open source offline map stack and attribution requirements used by the project.
