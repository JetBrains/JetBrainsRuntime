# The Skia backend (libawt_skia)

A Java2D pipeline on Skia's Graphite: `sun.java2d.skia` on the Java side, this library on the native
side, and a platform layer per window system (`SKPlatform.h`), which renders through one or more graphics
APIs (Windows: `src/java.desktop/windows/native/libawt_skia`, `Win32SKPlatform.h`, with Dawn over
Direct3D 11). Every module is a class or a namespace with one header, and the header carries the design
of its module. This file holds what spans the modules.

## Threads and locks

- The flusher thread (`SKRenderQueue`, "Java2D Skia Queue Flusher") interprets the render queue and owns
  the backend's state: the render state (`SKContext`), the contexts (`SKGraphicsConfig`), the caches, the
  batches, the presents (`SKPresentation`). Everything in `libawt_skia` runs on it unless a header says
  otherwise.
- The render queue's lock is the AWT lock (`RenderQueue.lock()`, `SunToolkit.awtLock()`): Java encodes
  operations under it, and `flushNow` hands them to the flusher while the caller holds it; the flusher's
  own timeout flush (100 ms) takes it with `tryLock`.
- The entry lock (`SKEntryLock.h`) serializes the JNI entry points that touch backend or platform state
  outside a flush (the configurations' creation and disposal, the surfaces' creation, the device-loss
  simulation, the counters) with the flush itself. The order is the AWT lock first, then the entry lock;
  the one entry without the entry lock, `isDeviceLost`, says why.
- A flush calls back into Java only to count a window's frames (`SKSurfaceData.countNewFrame`), which takes
  no AWT lock: a callback from a flush must not, since the caller of `flushNow` holds it and waits.
- Nothing that holds Graphite or device objects is destroyed at process exit (`SKNeverDestroyed.h`), and
  nothing made with a context's recorder outlives the context (`SKOwnedImage`, `SKGraphicsConfig.h`).

## Presenting from the flusher thread

The flusher presents the windows' swap chains, and the thread that replaces a window's surface (the
event thread, under the AWT lock) creates and configures them; the windows belong to AWT's toolkit thread.
DXGI may `SendMessage` to a swap chain's window from `Present`, `ResizeBuffers` and the chain's creation
(Microsoft, [Multithreading and DXGI](https://learn.microsoft.com/en-us/windows/win32/direct3darticles/dxgi-best-practices)),
and the Vulkan WSI's swap chain and present functions may do the same (Khronos, Win32 WSI). So the toolkit
thread must never wait for the flusher or for the event thread while these run, or the three threads
deadlock: the event thread holds the tree lock and the peer monitor and waits for the flusher
(`WComponentPeer.replaceSurfaceData`), the flusher waits in `Present` for the toolkit thread, and the
toolkit thread waits for one of those monitors.

By AWT's design it does not wait for them (`src/java.desktop/windows/native/libawt/windows`, as of 2026-11):

- Its synchronous calls into Java post events or hand the work to the event thread: `handlePaint` and
  `handleExpose` (`postEvent`), `replaceSurfaceDataLater`, `dynamicallyLayoutContainer`
  (`executeOnEventHandlerThread`), the cursor update (`WGlobalCursorManager.nativeUpdateCursor`,
  `updateCursorLater`), the heavyweight controls' `handleAction` and the like (`postEvent`),
  `notifyWindowStateChanged`, the font, menu, clipboard and dialog callbacks. `setBackground`, a peer
  monitor, runs only inside `CreateHWnd`, while the creating thread is in the peer's constructor.
  `Component.getLocationOnScreen`, the tree lock, is reached only on the event thread.
- It waits for another thread in one place, drag and drop (`WM_AWT_WAIT_FOR_SINGLE_OBJECT`, `awt_DnDDS.cpp`,
  `awt_DnDDT.cpp`): the drag source's and target's mutex. `SyncCall`, `InvokeFunction` and
  `WM_AWT_INVOKE_METHOD` make other threads wait for the toolkit thread, not the reverse. Modal size and
  move loops pump.
- It never takes the AWT lock.

JBR's Direct3D and Vulkan pipelines present from their flusher threads under the same rule (upstream's
Direct3D pipeline ran its whole flush on the toolkit thread; JBR-7051 moved it to a flusher thread). A hang
that violated the rule would show the three threads above in a thread dump; the remedy then is to present
from the toolkit thread (`AwtToolkit::InvokeFunction`), as upstream did.
