# UPnP Library Migration Plan — replacing the custom `:upnp` protocol stack with jUPnP

**Status:** Authoritative implementation plan, v1.0
**Author:** architect pass (Opus), for execution by the builder pass (Sonnet)
**Companion documents:** [ARCHITECTURE.md](ARCHITECTURE.md) (this plan supersedes §9 and §19.3), [PLAN.md](PLAN.md), [TESTING.md](TESTING.md)
**Repository state this plan was written against:** `master` @ `8ce64d9`

---

## 0. How to read this document

Sections 1–4 are analysis and design: read them once, in full, before touching code.
Section 5 is the executable roadmap: work through it in order, milestone by milestone.
Section 6 is the acceptance gate. Section 7 is the risk register — consult it when something
behaves unexpectedly on device, because most of the surprises are already listed there.

Every claim in §2 about jUPnP's internals was verified by reading the jUPnP 3.0.5 sources
(`git tag 3.0.5`) and the published POMs on Maven Central, not from memory. Where a fact
matters to an implementation decision, the source file is cited so it can be re-checked.

---

## 1. Executive Summary & Problem Analysis

### 1.1 The decision

Replace the hand-written SSDP / device-description / SOAP / DIDL-Lite implementation in the
`:upnp` module with **jUPnP 3.0.5** (`org.jupnp:org.jupnp` + `org.jupnp:org.jupnp.support`),
driven through a **hand-written transport layer** (OkHttp `StreamClient`, no `StreamServer`,
no Jetty, no servlet API) rather than through jUPnP's own `org.jupnp.android` artifact.

The public seams — `MediaServerDirectory`, `ContentDirectoryClient`, `MediaServer`,
`DidlContainer`, `DidlItem`, `Resource`, `BrowseResult`, `UpnpError` — **do not change**.
`:app`, `:vrcore` and `:playback` compile untouched. The swap is confined to the internals of
`:upnp` plus three small new files in `:app`.

### 1.2 Read this before you start: the reported hang is (mostly) not a UPnP bug

The reported symptom is: the server is reachable and is added successfully in the 2D
`SetupActivity` lobby, but selecting it on the in-VR Browse screen hangs forever in
`Loading…`.

**That specific symptom has a proximate cause in `:app` that has nothing to do with the UPnP
protocol stack, and migrating to jUPnP will not fix it on its own.**

`AppStateMachine` is a reducer-plus-effects machine. `dispatch()` reduces the event, stores the
new state, and publishes the returned effects onto a `SharedFlow`:

```kotlin
// app/src/main/java/com/daydreamvr/player/state/AppStateMachine.kt:34-41
private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 128)
val effects: SharedFlow<Effect> = _effects.asSharedFlow()

fun dispatch(event: Event) {
    val (next, fx) = reduce(_state.value, event)
    _state.value = next
    fx.forEach { _effects.tryEmit(it) }
}
```

Nothing ever collects `effects`. `VrActivity.wireFlows()` collects only `overlay.text` and
`decoder.connectedGamepads`; `effectRunner.run(...)` is called exactly once, by hand, for
`Effect.StartDiscovery` at `VrActivity.kt:206`. Verified:

```
$ grep -rn "\.effects" app/src/main app/src/test
(no matches)
```

Consequently, selecting a server on the server list runs this reduction —

```kotlin
// AppStateMachine.kt:339-344
i < state.servers.size -> {
    val server = state.servers[i]
    state.copy(
        screen = VrScreen.BROWSE,
        browse = BrowseState(listOf(BrowseFrame(server, "0", server.friendlyName))),
    ) to listOf(Effect.Browse(server, "0", PageRequest.DEFAULT))
}
```

— which creates a `BrowseFrame` whose `loading` defaults to `true` (`AppState.kt:65`) and emits
`Effect.Browse` into a flow with no subscriber. `tryEmit` returns `true` (there is buffer
capacity), the effect is silently dropped, no HTTP request is ever made, no `BrowseLoaded` or
`BrowseFailed` event ever arrives, and `loading` stays `true` forever. That is precisely
"hangs indefinitely in Loading…", and it would reproduce against a perfect UPnP stack.

The same defect silently disables **every** other reducer-emitted effect in VR: `Play`, `Seek`,
`SeekRelative`, `SetPlayWhenReady`, `StopPlayback`, `SetPlaybackSpeed`, `SelectAudioTrack`,
`SelectSubtitleTrack`, `Recenter`, `ApplySettings`, `AddManualServer` and `QuitToLobby`.

**Therefore Milestone 0 of this plan fixes the wiring and proves the current stack's real
behaviour, before any library is introduced.** This is not a detour: without it you cannot tell
whether jUPnP fixed anything, because the observable symptom is produced downstream of the
protocol layer. If Milestone 0 alone makes browsing work end-to-end, the migration is still
worth doing for the reasons in §1.3 — but you will be doing it with a working baseline and a
real error signal instead of a blind swap.

### 1.3 Why replace the custom stack anyway

The wiring bug explains the *hang*. It does not make the hand-written stack a good long-term
foundation. The independent case for migrating:

| Area | Custom `:upnp` today | jUPnP |
|---|---|---|
| Malformed device descriptions | `DeviceDescriptionParser` requires well-formed XML; a single unescaped `&` in a `friendlyName` (common on consumer NAS boxes) fails the whole device | `RecoveringUDA10DeviceDescriptorBinderImpl` retries with progressive repairs (strips illegal characters, fixes unescaped ampersands, drops trailing garbage) before giving up |
| SCPD / action metadata | Never fetched. The SOAP envelope is hand-built from a hardcoded argument order and a `serviceType` string scraped from the description | Fetches each service's SCPD, builds typed `Action` metadata, and marshals arguments against the *server's own* declared contract |
| SSDP socket model | One `MulticastSocket` bound to an ephemeral port, used for both sending M-SEARCH and receiving; passive NOTIFY listening opens a second socket via the same provider | Separate `DatagramIO` (ephemeral, unicast M-SEARCH responses) and `MulticastReceiver` (port 1900, NOTIFY alive/byebye) — the split the spec actually requires, and the split that avoids the Android port-1900 unicast problem the current code worked around by hand |
| Device lifecycle | `lastSeenEpochMs` is recorded but never expired; `byebye` handled, `CACHE-CONTROL: max-age` ignored | `RegistryImpl` maintains max-age expiry, re-discovery, and `remoteDeviceUpdated`/`Removed` callbacks |
| Interoperability surface | 6 test files, 5 captured DIDL fixtures | Exercised continuously by openHAB against a very large installed base of consumer devices |
| Escaped-DIDL handling | Hand-rolled single-pass unescaper (`DidlParser.unescapeXmlOnce`) | The `Result` argument is a UPnP `string` datatype; the SOAP layer decodes it once, then `DIDLParser` parses the payload |

The custom stack is not *bad* code — it is careful, well-commented, and well-tested against its
five fixtures. It is simply carrying a category of risk (every consumer device's XML quirks)
that is better amortised across a shared library.

### 1.4 What this migration deliberately does *not* adopt

We take jUPnP's **protocol core and DIDL model**. We do not take:

- **`org.jupnp:org.jupnp.android`** — see §2.4. Its `AndroidNetworkAddressFactory` reflects into
  `java.net.InetAddress`'s private `holder.hostName` field and **returns `false` for every
  address if that reflection fails**, which is exactly the failure mode Android's non-SDK
  interface restrictions produce on API 28+. Its `AndroidRouter` also registers a
  `BroadcastReceiver` with the two-argument `registerReceiver` overload. We reimplement both,
  correctly, in ~150 lines we control.
- **Jetty / the servlet API** — jUPnP's default transport. We supply an OkHttp `StreamClient`
  and no `StreamServer` at all (§2.5). This is a large win: it removes ~2 MB of dependencies,
  reuses the app's existing shared OkHttp connection pool exactly as ARCHITECTURE.md §2
  intends, and eliminates the "bind an HTTP server on a phone" failure class entirely.
- **GENA eventing** — we are a browse-only control point. We never subscribe, so we never need
  an inbound HTTP callback endpoint.
- **jUPnP's `Browse` action callback** (`org.jupnp.support.contentdirectory.callback.Browse`) —
  it only parses the DIDL when `NumberReturned > 0` (verified, `Browse.java:105`), and
  ARCHITECTURE.md §9.4 explicitly documents servers that return `NumberReturned=0` alongside a
  non-empty `Result`. We invoke the action directly and parse unconditionally (§3.4).

### 1.5 Net effect on the module boundary

`:upnp` **stays a pure `java-library`** with no `android.*` imports, so
`gradle/module-rules.gradle.kts`'s `checkNoAndroidImports` continues to pass unmodified. jUPnP
core is plain JVM code. The two things that genuinely need Android — the Wi-Fi multicast lock
and interface selection — stay in `:app` behind a small interface, exactly as
`DatagramChannelProvider` does today.
