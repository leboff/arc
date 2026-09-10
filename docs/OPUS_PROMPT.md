You are the architect in a two-stage 'Opus Plan, Sonnet Build' workflow.
Your task is to thoroughly analyze the repository, investigate Android-compatible UPnP/DLNA client libraries, and write an authoritative, production-grade migration plan in docs/UPNP_LIBRARY_PLAN.md to replace the custom :upnp module's discovery and ContentDirectory implementation with an established, battle-tested library.

### Current Problem & Context:
1. Target: Arc Daydream VR Player, an Android 15/16 (API 35+) Kotlin application for Daydream View / Cardboard VR headsets, streaming from home UPnP/DLNA servers (like Gerbera, MiniDLNA, Plex).
2. Symptom: The app successfully connects to the server (e.g. Gerbera at 192.168.50.10:49152) in the 2D SetupActivity lobby, but once entering VR and selecting the server on the Browse screen, it hangs indefinitely in the 'Loading…' state. SOAP Browse requests or DIDL-Lite parsing in our custom :upnp stack are fragile and failing on real Android runtime.
3. User decision: Stop patching the custom UPnP stack. Replace the underlying UPnP discovery and ContentDirectory client with an established library.

### Deliverable: docs/UPNP_LIBRARY_PLAN.md
Your plan must include:
1. Library Selection & Trade-Off Analysis:
   - Evaluate jUPnP (org.jupnp:jupnp-core - the maintained fork of Cling), Cling, CyberLink / CyberGarage, and any modern alternatives.
   - Choose the best library for Android API 34+ / Java 17 / Kotlin, noting license, Maven Central / Jitpack repository coordinates, and Android dependencies.
2. Architecture & Seam Integration:
   - How the chosen library maps onto our existing AppContainer, MediaServerDirectory, and ContentDirectoryClient interfaces so the rest of the app (:app state machine, :vrcore, :playback) remains clean and decoupled.
   - How SSDP discovery and device description fetching are handled.
   - How ContentDirectory Browse / DIDL parsing is handled (mapping library container/item objects to our DidlContainer / DidlItem models).
3. Android & Build Configuration:
   - Exact Gradle dependency coordinates and repository additions in build.gradle.kts / settings.gradle.kts.
   - ProGuard / R8 keep rules in app/proguard-rules.pro.
   - Android permissions, service lifecycle (e.g. AndroidUpnpService vs standalone client), and thread safety.
4. Step-by-Step Implementation Roadmap for Sonnet:
   - Numbered, granular phases/milestones with exact file paths and classes to create/modify.
   - Unit tests to write/adapt using MockWebServer or library mocks.
   - Clear acceptance criteria and build/test verification commands (e.g. ./gradlew test).

Explore the existing codebase to understand the interfaces and current implementation, formulate the plan, write it to docs/UPNP_LIBRARY_PLAN.md, and verify the file is written before exiting.
