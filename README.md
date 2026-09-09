# Daydream / Cardboard VR UPnP Player

An Android VR video player designed for Google Daydream / Cardboard viewers controlled entirely via Bluetooth gamepad / video game controller.

## Features
- **In-Headset Stereoscopic UI:** Left/Right eye split-screen HUD for browsing and controlling playback without removing the phone from the headset.
- **UPnP / DLNA Discovery & Browsing:** SSDP discovery and ContentDirectory XML traversal to browse local media servers (e.g. Gerbera, Synology, Plex DLNA) and stream direct video URLs.
- **Full Gamepad Navigation:** D-pad / Left Stick for list navigation, A to Select/Play, B to Go Back/Close, X for Play/Pause, Y for Recenter, Triggers/Bumpers for seeking.
- **3DOF Head-Tracking Virtual Theater:** Gyroscopic orientation with instant recentering so videos can be viewed on a floating virtual cinema screen.
- **Media3 / ExoPlayer Engine:** Wide codec support for network-streamed video.

## Dome projection and alignment

Use Projection in the player HUD or browse inspector to cycle flat modes, mono/SBS/OU domes at 180°, 190°, 200°, or 220°, and mono VR360. Filename detection combines VR180/180 with SBS/HSBS/H-SBS or OU/TB and defaults to 180°; choose wider domes manually. OU uses the top image for the left eye.

During playback, hold the left stick horizontally to rotate the theater or dome at up to 40°/second. Release to stop. D-pad navigation remains available, and the left stick navigates normally in browse screens. Recenter adjusts headset alignment while retaining your scene yaw offset; the offset lasts for the activity session.
