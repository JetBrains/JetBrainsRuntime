# Reuse lwawt for Wayland AWT Implementation: main stages

1. Reuse LWTookit for Wayland. They have more or less similar structure.
Possible issue: even macos implementation doesn't use part of LWTookit logic (even loop handling).
2. Add missing lightweight components to Wayland. We can more or less directly create components like LWTextField from WLToolkit.
A detailed plan is available in [plan1.md](plan1.md). Possible issues: we end up with 2 base classes for component peers,
one from lwawt for text fields, buttons, etc., and one from wayland for windows and frames.
3. Merge base classes for component peers somehow.
