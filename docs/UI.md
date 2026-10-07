# UI model

Modelled on Reqable's desktop layout, adapted to a phone, plus four things that
only exist because PinCatcher is an APK patcher rather than a generic proxy.

## Borrowed from Reqable

**Bottom status bar with live counts.** Reqable's desktop bottom bar shows total,
filtered and selected. On a phone that is the single most useful piece of chrome
in a traffic tool, and no other competitor has it. Carried over.

**A tab that hides itself when it has nothing in it.** Reqable omits the Query
tab on a request with no query parameters, the Cookie tab when there are none.
Copying this exactly: a tab strip that stays short and never shows an empty pane.

**Status dot pinned to the far left.** Reqable uses exactly three states -
green complete, yellow failed, grey in progress - and the dot never moves. Ours
carries the HTTP class instead, which is the more useful signal for an inspector,
but the position and the never-move rule are the same.

**A dense, sortable, monospaced list.** Reqable's list is a spreadsheet: columns
toggle, headers sort, the row is scannable in one glance. Monospace for anything
compared character by character.

**Raw and Raw2 as separate tabs.** Reqable decodes gzip bodies for the Raw tab
and keeps the untouched bytes in Raw2. Cheap to implement, and the only way to
actually debug a compression bug.

**Body viewers by content type.** JsonViewer, HexViewer, ImageViewer. Same
split, same names.

## Borrowed from Charles

**Focus.** Charles promotes the hosts you care about and collapses the rest under
"Other Hosts". PinCatcher's version is better than that, because the tool already
knows the target app: focus is implicit. Traffic from other apps is filtered out
by the per-app allowlist rather than by the user tidying a list by hand.

**Sequence versus structure.** Charles offers both. PinCatcher stays chronological
only. Structure view is a desktop affordance for correlating a long session; on a
phone the search bar does that job faster.

## Adapted away from the desktop

**Columns.** Reqable lets you show, hide, reorder and drag-resize columns. A
phone has no horizontal scroll without fighting the vertical scroll, so the list
shows the four that matter - status, method, host, path - and everything else
moves to the detail pane. A column toggle sheet is the escape hatch rather than
the default.

**Detail pane.** Reqable opens a resizable split pane, toggled by double-click.
On a phone that is a full-screen push. One tap opens it, back closes it.

## The four differentiators

These are the parts that no generic proxy has, because they all depend on knowing
what the client app *is* and what was *done to it*.

**1. Provenance badge on every row.** PinCatcher knows which package produced a
flow and what patch state that package is in - `native`, `NSC`, `smali`,
`flutter`. Reqable cannot show this; it is a transparent pipe and has no idea
what is on the other end. This is the whole reason the tool exists, so it is on
the row, not buried in a detail pane.

**2. Storage budget in the toolbar, not in a settings screen.** Reqable's desktop
has infinite disk. A phone does not. The traffic screen carries the ring-buffer
state inline, because "why did my capture stop" is a storage question far more
often than people expect.

**3. Pinning verdict per host.** For each host, whether the target app's pinning
was bypassed, or whether the flow arrived as cleartext, or whether QUIC was
dropped to force a TCP fallback. Directly actionable - it tells the user whether
their patch worked before they go looking.

**4. Patch record on the detail pane.** What PinCatcher did to this specific app:
which NSC config was injected, which methods were stubbed, whether the Flutter
engine was swapped. Reqable's "Interceptor" tab shows rule ordering; ours shows
whether the instrumentation is even in place yet.

## Screen structure

```
Bottom navigation
  Capture    state, start/stop, CA status, storage budget
  Traffic    the main content  (Reqable's Traffic tab)
  Apps       pick a target package
  Settings

Traffic  ->  search/filter bar
        ->  sortable column header
        ->  dense list: dot | method | status | host/path | provenance badge
        ->  bottom status bar: total | filtered | selected

Flow detail (full screen)
  Overview    url, method, status, protocol, remote, TLS, timing, size
  Query       hidden when absent
  Headers     always shown
  Body        always shown; JSON / Hex / Image sub-viewers
  Raw         decoded protocol message
  Raw2        untouched bytes
  WebSocket   replaces Body when the flow is a WS upgrade
  Patch       what was changed in the client app to make this visible
```