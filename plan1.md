# Plan: Reuse lwawt for Wayland AWT Implementation

## Overview

**The idea**: lwawt (lightweight AWT) for macOS was designed as a platform-agnostic AWT implementation with clean abstraction interfaces. It can be reused for Wayland to:
1. **Eliminate code duplication** - reuse 20+ component peer implementations
2. **Implement missing AWT components** - TextField, TextArea, Label, Checkbox, List, etc. via Swing delegation
3. **Improve maintainability** - single implementation for all platforms

**Strategy**: Gradual migration - start with text components (TextField, TextArea), migrate others later.

## Current State

### lwawt (macOS)
- **Location**: `src/java.desktop/macosx/classes/sun/lwawt/`
- **Swing delegation**: `LWTextFieldPeer` → `JPasswordField`, `LWTextAreaPeer` → `JTextArea`, etc.
- **Key abstractions**: `PlatformWindow`, `PlatformComponent`, `PlatformEventNotifier`, etc.

### Wayland
- **Location**: `src/java.desktop/unix/classes/sun/awt/wl/`
- **Implemented**: Window management, events, graphics (Vulkan/SHM), clipboard
- **Missing**: TextField, TextArea, Label, Checkbox, Choice, List, Scrollbar, ScrollPane, Panel, Menus

## Architecture

### Key Challenges

#### 1. `LWComponentPeer` has hard dependencies on concrete classes:
```java
private final LWContainerPeer<?, ?> containerPeer;  // Cast fails on Wayland!
private final LWWindowPeer windowPeer;
```

On Wayland, Frame peer is `WLFramePeer`, not `LWContainerPeer` - the cast fails.

Solution: Extract Interfaces

- `LWWindowPeerProvider` - what component peers need from their window:
```java
interface LWWindowPeerProvider {
    Graphics getOnscreenGraphics(Color fg, Color bg, Font f);
    GraphicsConfiguration getGraphicsConfiguration();
    Point getLocationOnScreen();
    void addDropTarget(DropTarget dt);
    void removeDropTarget(DropTarget dt);
}
```

- `LWContainerPeerProvider` - what component peers need from their container:
```java
interface LWContainerPeerProvider {
    LWWindowPeerProvider getWindowPeerOrSelf();
    void addChildPeer(LWComponentPeer<?, ?> child);
    void removeChildPeer(LWComponentPeer<?, ?> child);
    void setChildPeerZOrder(LWComponentPeer<?, ?> peer, LWComponentPeer<?, ?> above);
    Region getRegion();
    Rectangle getContentSize();
    Region cutChildren(Region r, LWComponentPeer<?, ?> above);
    boolean isEnabled();
    boolean isShowing();
}
```

#### 2. LWAWT has macOS-specific logic

Solution: abstract it away.

## Implementation Steps

### Step 1: Modify LWComponentPeer to use interfaces

```java
// Before:
private final LWContainerPeer<?, ?> containerPeer;
private final LWWindowPeer windowPeer;

// After:
private final LWContainerPeerProvider containerPeer;
private final LWWindowPeerProvider windowPeer;
```

### Step 2: Abstract macOS-specific code

**LWComponentPeer.java** has these macOS-specific dependencies to abstract:
```java
// Lines 69, 75, 77-78 - imports:
import sun.awt.CGraphicsEnvironment;
import sun.java2d.MacOSFlags;
import sun.java2d.metal.MTLRenderQueue;
import sun.java2d.opengl.OGLRenderQueue;

// Line 1292 - Metal display sync check:
if (MacOSFlags.isMetalEnabled() && !MacOSFlags.isMetalDisplaySyncEnabled())

// Lines 1407-1408 - RenderQueue selection:
RenderQueue rq = CGraphicsEnvironment.usingMetalPipeline() ?
        MTLRenderQueue.getInstance() : OGLRenderQueue.getInstance();
```

**LWWindowPeer.java** has:
```java
// Line 67 - import:
import sun.awt.CGraphicsDevice;

// Line 560 - screen insets:
((CGraphicsDevice) config.getDevice()).getScreenInsets()
```

**LWToolkit.java** has:
```java
// Line 36 - import:
import sun.java2d.MacOSFlags;

// Line 367 - Metal check:
return MacOSFlags.isMetalEnabled() && !MacOSFlags.isMetalDisplaySyncEnabled();
```

**Abstraction approach**:
1. Create `LWPlatformUtils` interface with methods like:
   ```java
   interface LWPlatformUtils {
       boolean needsRenderQueueSync();
       void syncRenderQueue();
       Insets getScreenInsets(GraphicsConfiguration gc);
   }
   ```
2. Provide `MacOSPlatformUtils` (existing behavior) and `WaylandPlatformUtils` implementations
3. Inject via `LWToolkit.getPlatformUtils()` or constructor

### Step 3: Move lwawt classes to `share/classes`

Create `src/java.desktop/share/classes/sun/lwawt/`:
```
sun/lwawt/
├── LWWindowPeerProvider.java      # NEW interface
├── LWContainerPeerProvider.java   # NEW interface
├── PlatformComponent.java         # MOVE from macosx
├── LWComponentPeer.java           # MOVE + MODIFY (use interfaces)
├── LWContainerPeer.java           # MOVE + MODIFY (implement interface)
├── LWTextComponentPeer.java       # MOVE
├── LWTextFieldPeer.java           # MOVE
├── LWTextAreaPeer.java            # MOVE
└── LWRepaintArea.java             # MOVE
```

### Step 4: Create Wayland adapters

Create `src/java.desktop/unix/classes/sun/awt/wl/lwawt`:
```
sun/awt/wl/lwawt/
├── WLWindowPeerAdapter.java       # Adapts WLWindowPeer → LWWindowPeerProvider
├── WLContainerPeerAdapter.java    # Adapts WLFramePeer → LWContainerPeerProvider
└── WLLWPlatformComponent.java     # No-op PlatformComponent
```

### Step 5: Wire into WLToolkit, use the code pattern from LWToolkit.java

```java
// In WLToolkit.java
@Override
public TextFieldPeer createTextField(TextField target) {
    Container container = SunToolkit.getNativeContainer(target);
    WLComponentPeer containerPeer = (WLComponentPeer) targetToPeer(container);
    LWWindowPeerProvider windowProvider = new WLWindowPeerAdapter(getWindowPeer(containerPeer));
    LWContainerPeerProvider containerProvider = new WLContainerPeerAdapter(containerPeer, windowProvider);
    PlatformComponent pc = new WLLWPlatformComponent();

    LWTextFieldPeer peer = new LWTextFieldPeer(target, pc, containerProvider);
    peer.initialize();
    return peer;
}
```

### Step 6: Update build system

- Update `module-info.java` to export `sun.lwawt`
- Ensure shared classes compile for all platforms

## Files to Modify

**Shared (new)** - `src/java.desktop/share/classes/sun/lwawt/`:
- `LWWindowPeerProvider.java` - NEW interface
- `LWContainerPeerProvider.java` - NEW interface
- `LWPlatformUtils.java` - NEW interface for platform-specific utils
- `PlatformComponent.java` - COPY from macosx
- `LWComponentPeer.java` - COPY + MODIFY (use interfaces, remove macOS imports)
- `LWContainerPeer.java` - COPY + MODIFY (implement interface)
- `LWTextComponentPeer.java`, `LWTextFieldPeer.java`, `LWTextAreaPeer.java` - COPY
- `LWRepaintArea.java` - COPY

**macOS (keep + adapt)** - `src/java.desktop/macosx/classes/sun/lwawt/`:
- `LWWindowPeer.java` - implement `LWWindowPeerProvider`
- `MacOSPlatformUtils.java` - NEW, implement `LWPlatformUtils` with existing Metal/OGL logic

**Wayland (new)** - `src/java.desktop/unix/classes/sun/awt/wl/lwawt`:
- `WLWindowPeerAdapter.java` - adapts WLWindowPeer → LWWindowPeerProvider
- `WLContainerPeerAdapter.java` - adapts WLFramePeer → LWContainerPeerProvider
- `WLLWPlatformComponent.java` - no-op PlatformComponent
- `WaylandPlatformUtils.java` - implement `LWPlatformUtils` for Vulkan/SHM

**WLToolkit**:
- `src/java.desktop/unix/classes/sun/awt/wl/WLToolkit.java` - wire text peers

## Verification

### Build
```bash
# Linux
bash configure && make images
./build/linux-x86_64-server-release/images/jdk/bin/java -version

# macOS (no regression)
bash configure && make images
```

### Test
```bash
make test TEST="jdk/java/awt/TextField"
make test TEST="jdk/java/awt/TextArea"
```

### Manual Test
```java
// AWTTextTest.java
import java.awt.*;
import java.awt.event.*;

public class AWTTextTest {
    public static void main(String[] args) {
        Frame f = new Frame("AWT Text Test");
        f.setLayout(new FlowLayout());
        f.add(new TextField("Type here", 30));
        f.add(new TextArea("Multi-line\ntext", 5, 40));
        f.pack();
        f.setVisible(true);
        f.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { System.exit(0); }
        });
    }
}
```

## Future Phases

After text components work:
- **Phase 2**: Label, Button, Checkbox
- **Phase 3**: List, Choice, Scrollbar
- **Phase 4**: Evaluate full Window/Frame peer migration
- **Phase 5**: Menu components

## Future Unification Path (Long-term)

The current plan creates two coexisting peer hierarchies:
- `LWComponentPeer` → Swing-delegated components (TextField, Button, etc.)
- `WLComponentPeer` → Heavyweight windows with native Wayland surfaces

**Key similarities** between the base classes:
- Both have `target`, `stateLock`, `background`, `visible`, paint area
- Both implement `ComponentPeer`
- Similar methods: `setBackground()`, `setVisible()`, `getGraphics()`, etc.

**Key differences**:
- Surface ownership: LWComponentPeer borrows from window; WLComponentPeer owns surface
- Swing delegation: LWComponentPeer has delegate JComponent; WLComponentPeer doesn't
- Parent refs: LWComponentPeer tracks containerPeer/windowPeer; WLComponentPeer doesn't

**Gradual unification path**:
1. **Current**: Adapters bridge between hierarchies
2. **Next**: WLWindowPeer implements `LWWindowPeerProvider` directly (eliminate adapters)
3. **Later**: Replace WLButtonPeer/WLCanvasPeer with lwawt versions if Swing delegation is acceptable
4. **Long-term**: Extract common `ComponentPeerBase` interface or abstract class

## TODOs

### Extract Common Delegates for Code Reuse

**Problem**: `LWContainerPeer` uses `super` calls to `LWComponentPeer`, making it hard to reuse container logic in Wayland without inheritance.

**Proposed Solution**: Use composition instead of inheritance for shared logic:

1. **Extract `CommonComponentPeer`** - delegate class containing component logic currently in `LWComponentPeer`:
    - State management (bounds, visibility, enabled)
    - Paint area management
    - Region calculations
    - Methods that `LWContainerPeer` currently calls via `super`

2. **Extract `CommonContainerPeer`** - delegate class containing container logic currently in `LWContainerPeer`:
    - Child peer list management (`childPeers`)
    - `addChildPeer`, `removeChildPeer`, `setChildPeerZOrder`
    - `cutChildren`, `findPeerAt` (searching children)
    - `repaintChildren`, `getVisibleRegion`
    - Property propagation to children

3. **Structure**:
   ```
   CommonComponentPeer (extracted component logic)
   CommonContainerPeer (extracted container logic)
        │
        └── holds reference to CommonComponentPeer
            (uses this instead of super.xxx() calls)

   LWComponentPeer {
       CommonComponentPeer commonComponent;  // delegates to this
   }

   LWContainerPeer extends LWComponentPeer {
       CommonContainerPeer commonContainer;  // constructed with ref to commonComponent
   }

   WLFramePeer extends WLComponentPeer {
       CommonContainerPeer commonContainer;  // reuses same container logic!
   }
   ```

4. **Benefits**:
    - True code reuse - both macOS and Wayland use same container/component logic
    - No awkward `super` calls - `CommonContainerPeer` calls `commonComponentPeer.getVisibleRegion()`
    - Platform-specific parts stay in LW*/WL* classes
    - Testable independently

### LWGraphicsConfig Contract for Wayland

**Issue**: `LWComponentPeer.getLWGC()` returns `LWGraphicsConfig` interface, but the code assumes that the graphics configuration both:
1. Implements the `LWGraphicsConfig` interface
2. Extends the abstract `GraphicsConfiguration` class

This is an implicit contract that Wayland's graphics configuration (`WLGraphicsConfig`) must follow to work with lwawt code. The `getLWGC()` method casts `getGraphicsConfiguration()` result to `LWGraphicsConfig`:
```java
public final LWGraphicsConfig getLWGC() {
    return (LWGraphicsConfig) getGraphicsConfiguration();
}
```

**Action**: Ensure `WLGraphicsConfig` implements `LWGraphicsConfig` interface while extending `GraphicsConfiguration`.

### LWWindowPeer/LWComponentPeer Functionality Analysis for Wayland

After analyzing `LWWindowPeer` and `LWComponentPeer`, here's what Wayland actually needs vs. what it already has or doesn't need:

#### NEEDED - WL is missing this functionality:

| Feature | LW Methods | Why needed |
|---------|-----------|------------|
| **Mouse dispatch to children** | `findPeerAt()`, `notifyMouseEvent()`, `generateMouseEnterExitEventsForComponents()` | WL sends all events to Window, not to the actual child component under cursor |
| **Mouse tracking state** | `lastMouseEventPeer`, `mouseDownTarget[]`, `lastCommonMouseEventPeer`, `mouseClickButtons` | Required for proper enter/exit events and drag tracking |
| **Window grab/ungrab** | `grabbingWindow`, `grab()`, `ungrab()` | Needed for popup menus, combo boxes |
| **Drop target management** | `addDropTarget()`, `removeDropTarget()` | WL doesn't implement drag-and-drop peer support |
| **Coordinate conversion** | `windowToLocal()`, `localToWindow()` | Needed for mouse event coordinate translation |

#### WL HAS ITS OWN - Different implementation already exists:

| Feature | LW approach | WL approach |
|---------|-------------|-------------|
| Surface data | `platformWindow.replaceSurfaceData()` | Direct `wlSurface` management |
| Bounds | `bounds` field, `platformWindow.setBounds()` | `wlSize` with HiDPI tracking |
| Visibility | Via platform window | `wlSetVisible()` with Wayland threading |
| Paint events | `RepaintArea`, delegate-based | `WLRepaintArea`, direct |
| Graphics | Via delegate | Direct from `WLGraphicsConfig` |
| Layout | Delegate container | Own implementation |
| Resize notification | `notifyReshape()` | `notifyConfigured()` |
| Cursor | `LWCursorManager` | Own cursor handling |
| Full screen | `platformWindow.enterFullScreenMode()` | Native Wayland calls |
| Window state | `windowState` field | Native in `WLFramePeer` |
| Focus handling | Complex `requestFocus()` | Simpler focus tracking |
| Mouse events | Creates events for child component | Creates events for Window only |

#### NOT APPLICABLE - WL doesn't need:

| Feature | Why not needed |
|---------|---------------|
| **Swing delegate** (`delegate`, `delegateContainer`, `sendEventToDelegate()`) | WL doesn't use Swing component delegation for windows |
| **Platform window abstraction** | WL manages Wayland surfaces directly |
| **Platform component** | Same as above |
| **Maximized bounds** | Wayland compositor decides |
| **setZOrder(), toFront(), toBack()** | Wayland doesn't allow this |
| **Window positioning** | Wayland doesn't allow programmatic positioning |
| **Container peer hierarchy** | WL windows are always top-level surfaces |
| **textured**, non-opaque background painting | macOS-specific |

#### Recommendation: Extract Useful Parts into Utilities

Rather than full inheritance from `LWWindowPeer`, extract just the useful parts:

1. **MouseEventDistributor** - Extract from `LWWindowPeer`:
   - `findPeerAt()` (from `LWContainerPeer`)
   - `notifyMouseEvent()`
   - `generateMouseEnterExitEventsForComponents()`
   - Mouse tracking state (`lastMouseEventPeer`, `mouseDownTarget[]`, etc.)
   - Enter/exit event generation logic

2. **WindowGrabManager** - Extract grab/ungrab logic:
   - `grabbingWindow` static field
   - `grab()`, `ungrab()`, `isGrabbing()`
   - Grab notification to other windows

3. **CoordinateConverter** - Extract coordinate utilities:
   - `windowToLocal()`
   - `localToWindow()`

4. **DropTargetManager** - Extract drop target handling:
   - `addDropTarget()`, `removeDropTarget()`
   - `fNumDropTargets`, `fDropTarget` tracking

#### Key Methods in LWWindowPeer.notifyMouseEvent() that WL needs:

```java
// 1. Find target component under mouse
LWComponentPeer<?, ?> targetPeer = findPeerAt(r.x + x, r.y + y);

// 2. Generate enter/exit for child components
generateMouseEnterExitEventsForComponents(when, button, x, y, ...);

// 3. Track drag targets
if (id == MouseEvent.MOUSE_PRESSED) {
    mouseDownTarget[targetIdx] = targetPeer;
} else if (id == MouseEvent.MOUSE_DRAGGED) {
    targetPeer = mouseDownTarget[targetIdx];  // Events go to press target
}

// 4. Convert to local coordinates
Point lp = targetPeer.windowToLocal(x, y, this);

// 5. Post to correct component (not window!)
postEvent(new MouseEvent(targetPeer.getTarget(), ...));
```

WL currently posts all mouse events to `getTarget()` (the Window), which means child components don't receive proper mouse events at the peer level.
