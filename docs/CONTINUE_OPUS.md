Now write the complete, authoritative docs/UPNP_LIBRARY_PLAN.md based on your research.

It must include:
1. Executive Summary & Problem Analysis (why custom stack failed on real Android vs why a library solves it).
2. Library Selection & Trade-Off Analysis:
   - Deep evaluation of jUPnP (org.jupnp:jupnp-core / org.jupnp.support, 2.7.x or 3.x, Android compatibility, Jetty vs custom OkHttp/Sun transport).
   - Cling legacy vs jUPnP vs CyberGarage / CyberLink.
   - Recommended library, exact Maven Central / JitPack coordinates, license (EPL / LGPL).
3. Architecture & Seam Integration:
   - Mapping jUPnP Service / Device / ContentDirectory onto our existing AppContainer, MediaServerDirectory, and ContentDirectoryClient interfaces.
   - How SSDP discovery and device description fetching work with jUPnP on Android 14/15/16.
   - How ContentDirectory Browse / Search & DIDL parsing work (mapping to our DidlContainer / DidlItem).
4. Android & Build Configuration:
   - Exact build.gradle.kts dependencies and repository setup.
   - R8 / ProGuard keep rules for jUPnP and dependencies.
   - Android permissions, background thread lifecycle, MulticastLock, and Wi-Fi binder integration.
5. Step-by-Step Implementation Roadmap for Sonnet:
   - Granular milestones with exact file paths and class changes.
   - Verification tests and commands.

Write out the full file to docs/UPNP_LIBRARY_PLAN.md now and verify it exists before finishing.
