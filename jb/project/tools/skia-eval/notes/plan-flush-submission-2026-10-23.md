# Plan: fewer GPU submissions and presents per flush (x64hw, 2026-10-23)

Research and risk analysis before any change. Question from the flush comparison (raw record
2026-10-23): Skia's flush costs 1.66 ms against Direct3D 9's 0.65 ms, and about three quarters of
it is the hand-over to the GPU (submit 0.50 ms, insertRecording 0.41, present 0.37, snap 0.10 per
flush). Proposal 1 was "submit only when a present or a sync needs it". This note says what the
backend does today, how the four other GPU pipelines handle the same step, what proposal 1 could
gain, what it risks, and what to do in which order.

## 1. What the Skia backend does today

Every queue entry (`SKRenderQueue_flushBuffer`, and the direct entry points `drawPoly`,
`drawShape`, `maskFill`, `drawGlyphList`) ends in `SKBackend_EndOfFlush`:

1. for every context drawn into (`touchedContexts`): draw the pending batches, `Recorder::snap()`,
   `Context::insertRecording()`, `Context::submit(SyncToCpu::kNo)`;
2. for every window surface drawn into (`touchedWindows`): `SKPlatform_RequestRedraw`, on Windows a
   command encoder that copies the surface texture into the swap chain's current image, a queue
   submit of that copy, and `Present()` (present mode Mailbox, else Immediate: no vblank wait).

Special cases: `SYNC` submits with `SyncToCpu::kYes`; a surface-to-software blit (`getSnapshot`,
Robot) inserts and submits its own context with a CPU wait and reads the pixels back; a blit whose
source lives in another configuration's context submits that context first, with a CPU wait
(`SKBackend_Submit(src->state->cc, true)` in `sourceUsable`); `SKBackend_DisposeContext` submits
with a wait before it frees. The `FLUSH_BUFFER` opcode (Java's `SurfaceData.flush()`, sent by
`RepaintManager.updateWindows` when a paint cycle ends) is a no-op in the Skia queue: the window is
presented by whichever flush touched it.

The queue's Java side: `flushNow()` blocks its caller (the EDT for painting) until the flusher has
run the whole entry, presents included; the flusher also flushes on its own after 100 ms of
unflushed work. All Graphite contexts of one adapter share one Dawn device and one queue
(`SKPlatform_SameDevice`, `gpu->queue`), so their submissions execute in submission order.

Measured in the IDE (A3, scene session, 362 s): 7465 queue entries, about 5941 of them presenting
(80 %), 1524 not (20 %). Flusher time 12.6 s, 1.69 ms per entry: submit 3.54 s, insert 2.92 s,
present 2.18 s, snap 0.72 s, the drawing ops about 3.1 s.

## 2. How the other pipelines handle it

| pipeline | per queue flush | when the window is presented | CPU wait |
|---|---|---|---|
| Direct3D 9 (`D3DRenderQueue.cpp`) | `EndScene()` (a hint; "not really enough to flush the whole d3d pipeline") | not per flush: `SWAP_BUFFERS` from `D3DScreenUpdateManager.swapBuffers` for the dirty region, driven by Swing's paint cycle (`RepaintManager.updateWindows`), and `displayAllBuffersContent()` before a `SYNC`; `Present()` with no vblank wait | only `SYNC` |
| Metal (`MTLRenderQueue.m`) | commits the command buffer at the end of every flush (`MTLRenderQueue_reset`), plus intermediate commits after `RESET_PAINT` and mask-op runs where a state needs them (`canCoalesceCommit`) | not per flush: `FLUSH_BUFFER` → `[layer startRedraw]`; the layer blits to its drawable on the next CVDisplayLink callback (`displaySync`, default on), at most once per vblank | only `SYNC` / shape-clip spans |
| OpenGL (`OGLRenderQueue.c`) | `glFlush()` (a hint, no wait) | no present for windows: on-screen rendering draws into the window's framebuffer; `SWAP_BUFFERS` only for `BufferStrategy` flips | `glFinish()` on `SYNC` |
| Vulkan (`VKRenderQueue.c`, `VKRenderer.c`) | one `vkQueueSubmit` of the recorded command buffer per flush and device (`VKRenderer_Flush`) | not every touched window: `FLUSH_BUFFER` → `VKRenderer_FlushSurface` marks the surface, and the flush's submit presents the marked swap chains (`vkQueuePresent` in the same `VKRenderer_Flush`) | `VKRenderer_Sync` on `SYNC` |
| **Skia (today)** | snap, insertRecording, submit per touched context | **every touched window, every flush** (copy + submit + Present) | `SYNC`, readbacks, cross-context sources, dispose |

Two things stand out. First, every other pipeline presents a window **once per paint cycle, on an
explicit signal** (`SWAP_BUFFERS` / `FLUSH_BUFFER`), not on every queue flush; Skia is the only one
that presents per touched window per flush, and ignores the signal the queue already carries.
Second, the submit itself is per flush in Vulkan and Metal too (Direct3D 9's `EndScene` is cheap
because the driver batches), and the one attempt to coalesce submits, Metal's
`sun.java2d.metal.coalesceCommits` (ledger 2026-10-15), **made the IDE scenes slower** (flush 8.8 s
against 7.3 s): frequent commits overlap CPU encoding with GPU execution and let the texture pool
recycle from completed buffers. That precedent is the main caution against proposal 1.

## 3. What proposal 1 could gain, and what it cannot

- Submits that go away: the 20 % of flushes that present nothing. 1524 × (snap 0.10 + insert 0.41
  + submit 0.50) ≈ **1.5 s of the 12.6 s** of flusher time (about 7 % of Skia's 20.0 s of paint
  time). That does not close the 6.9 s gap to Direct3D 9. The software blits of Presentation Mode
  (one flush and submit per image) are in this 20 % and would be covered.
- Presents are the other lever: presenting once per paint cycle (Swing's `FLUSH_BUFFER`) instead
  of per flush, the other pipelines' model, drops presents from about 5941 to the number of paint
  cycles (4966 `paintDirtyRegions` calls), and lets the flushes between two presents share one
  snap + insert + submit. Estimate: 4966 × 1.38 ms ≈ 6.9 s instead of 7465 × 1.38 ≈ 10.3 s, about
  **3.4 s**. Both together: about 4 to 5 s of the 6.9 s gap, if the per-flush costs stay constant.
- The rest is the size of the hand-over itself: 1.38 ms for snap + insert + submit + present
  against 0.65 ms for Direct3D 9's whole flush. Whether that is Graphite's command building,
  Dawn's translation to D3D11, the texture copy into the swap chain image, or the D3D11 driver, no
  measurement so far says; nothing in proposals 1 or 2 changes it. Only a native profile of one
  flush can (WPR/ETW with symbols, an elevated shell).

## 4. Risks of deferring submits and presents, with mitigations

| # | risk | why it is real here | mitigation |
|---|---|---|---|
| R1 | a texture read before its producer's work ran: contexts of one adapter share one Dawn queue, so a consumer's submit must come after the producer's | cross-configuration blits exist (two displays, two configurations); today `sourceUsable` submits the source context with a CPU wait | before any submit, readback, sync or dispose, submit every pending context in the order their work was recorded (one list, not a set) |
| R2 | a pending recording references a platform texture that is freed | `SKBackend_ReleaseSurface(dispose)` and the platform's dispose free Dawn textures; a deferred recording would hold them by Graphite's resource refs, but the swap chain image copy is outside Graphite | dispose paths submit everything pending first (DisposeContext already does, with a wait) |
| R3 | memory growth: unsubmitted recordings keep upload buffers (every software image copy), scratch textures and the atlases' pending uploads alive; Graphite's recorder budget is 256 MB by default and resources recycle only after submit and `checkAsyncWorkCompletion` | a program that only draws into off-screen images and never presents (headless rendering to a VolatileImage, read back once at the end) would defer for ever | a cap: submit when the pending recordings exceed N or their uploads exceed X bytes, and the flusher's 100 ms timeout flush submits unconditionally |
| R4 | less CPU/GPU overlap and more latency: the GPU idles until the presenting flush, which then carries the frame's whole work; `flushNow()` keeps the EDT waiting for it | Metal's coalescing was slower for this reason; Skia's recordings are large (a frame) rather than many small command buffers | measure paint time and the latency from the last paint op to the present, not only flusher time; submit without a present is still allowed when the pending work is large (the cap in R3 doubles as this) |
| R5 | a present that misses content: a window touched by a flush that did not present would show stale pixels until the paint cycle ends | this is the other pipelines' behaviour, and Swing always ends a paint cycle with `updateWindows` → `FLUSH_BUFFER`; AWT heavyweights and `BufferStrategy` flips use `SWAP_BUFFERS`; a program that draws to a window outside Swing's cycle (a plain `Frame.paint` with `Toolkit.sync`) gets its present from `SYNC` | present on `FLUSH_BUFFER`, `SWAP_BUFFERS` and `SYNC`, and keep the 100 ms timeout flush presenting, as the safety net for everything else; `TdrProbe`-style windows that repaint by a timer go through Swing and are covered |
| R6 | readback semantics: `getSnapshot`, Robot and surface-to-software blits must see every draw, including other contexts' | today each readback submits its own context with a wait | R1's rule covers it (submit all pending, in order, before the readback's own wait) |
| R7 | device loss: pending recordings on a lost device | `insertRecording` fails and is logged; the device-lost path recreates surfaces and the IDE repaints | drop pending work on loss, as today's failed inserts are dropped |
| R8 | the shared branch: the backend runs on Metal too (`MTLSKPlatform.mm`, where presenting is `startRedraw` on the layer) | a change in `SKBackend_EndOfFlush` is platform-independent | the Mac must re-measure before the shared branch takes it; keep the behaviour behind a property for the comparison (`sun.java2d.skia.present=flush|cycle`, say), default unchanged until measured on both |

What is not a risk: pixel output. Deferring submits and presents changes when pixels reach the GPU
and the screen, not what they are; the atlases order their uploads by recording (generation), not
by submit. RenderMatrix, jtreg and LineConformance must stay identical all the same.

## 5. Order of work

1. **Native profile of one flush first** (needs the user: WPR/ETW, elevated). One IDE scene run
   and one `SyncProbe` run with CPU sampling on the flusher thread, symbols for `libawt_skia`,
   Dawn and the D3D11 runtime. Decides whether the 1.38 ms is ours (Graphite command building, the
   copy into the swap chain image) or the driver's, and so whether proposals 1 and 2 are the right
   levers at all. Half a day.
2. **Present per paint cycle** (the other pipelines' model), as a measured experiment behind a
   property: honour `FLUSH_BUFFER`, `SWAP_BUFFERS` and `SYNC`; a touched window no longer presents
   per flush; the timeout flush still presents. Submit stays per flush in this step, so R1 to R4
   do not arise yet; only R5 does. Judge: the IDE flush comparison (n = 5, `paintDirtyRegions`,
   `flushBuffer`, `J2D_SKIA_STATS` presents per second) plus a latency probe (paint op to
   present), RenderMatrix and jtreg unchanged, the TDR run, `awt.window.counters` frame rates.
   Expected: present 2.2 s → about 1.1 s. One day.
3. **Submit per present** (proposal 1, widened): snap + insert + submit only when presenting,
   syncing, reading back, disposing, or when the cap of R3 is hit; pending contexts submitted in
   recording order (R1). Expected together with step 2: 4 to 5 s of the 6.9 s. Judged the same way;
   Metal's precedent means the result may be negative, which is a result. One to two days.
4. Only then, if step 1 points at the copy: **render into the swap chain image** (proposal 2), a
   change of the window surface model, out of this plan's scope.

Steps 2 and 3 stay on the x64hw branch behind their property until the Mac has measured them.

## 6. Outcome of step 2 (2026-10-26)

Implemented as `sun.java2d.skia.present=cycle` (opt-in). n = 5 in the IDE scenes: paint time
−1.0 % (within the spread), flush time −3.1 %, flush calls +75 %; paint-to-present latency
unchanged (0.02 ms, LatencyProbe). The estimate in §3 did not hold: Swing's back-buffer blit
already ends each paint cycle with a flush, so presenting per cycle adds flushes instead of
removing them. Step 3 (submit only when presenting, syncing, reading back or over the cap) is now
the lever; under `cycle` it would drop the extra flushes' submits. Details in raw-x64hw-2026-10-23.md.

## 7. Outcome of step 3 (2026-10-26)

Implemented as `sun.java2d.skia.submit=present` (opt-in). n = 5 in the IDE scenes, alone and with
`present=cycle`: paint and flush time unchanged within the spread (−0.9 % at best). 80 % of the
flushes present, so 17 % of the submits can be deferred, and about half of a submit's cost moves
into the next one. Steps 2 and 3 together do not close the gap; the cost per flush does not come
from how often Skia hands over but from what one hand-over costs (submit 0.54 ms, present 0.37 ms).
Next: a native profile of a scene run, then step 4 (render into the swap chain image) if the
present copy shows up, or a look into what submit does on Dawn/D3D11.

## 8. Present off the flusher, rendering into the swap chain (2026-10-26)

Both measured or analysed, neither pays: Dawn holds its device lock across DXGI's `Present()`,
so a present thread only moves the compositor's wait into the flusher's next Dawn call (PresentProbe:
insert 4 → 935 ms per second); and Dawn's FLIP_DISCARD swap chain does not keep a window's
content, which Swing's partial repaints need, so the retained texture and its copy stay. The lever
that did pay was the render-pass count (`lcd=twopass`, −25 % flush time). Remaining: the submit's
cost per call, to be profiled.
