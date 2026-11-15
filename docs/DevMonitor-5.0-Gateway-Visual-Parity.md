# DevMonitor 5.0 Gateway — Visual Parity Standard

## Release requirement
The Gateway must not be released as a bare prototype. Its Android UI must reuse the established DevMonitor visual language and interaction patterns as closely as the separate Gateway edition permits.

## Reference audit completed
The latest available Android source archive on the development branch, DevMonitor-An-v337-4.7.0-TARGET-KOTLIN-RECHECK-CONTINUE-FIX.zip, was retrieved and inspected internally. The reference app is Jetpack Compose/Material 3 and uses the Aurora design system.

Verified design tokens:
- Deep navy backgrounds: #071426, #0B1D33, panel #10243D.
- Primary blue #147BFF, bright blue #3D9BFF, cyan #16D9F5, violet #8B5CF6.
- Green success #20D889, emerald #10B981, mint #5EEAD4.
- Light surfaces #F6FAFF, white cards, pale blue borders #DDE8F5.
- Large rounded glass cards, gradient action buttons, outlined gradient-accent buttons, luminous status indicators and live dots.
- The main app uses a dark navy top toolbar, modal navigation drawer, and dark bottom navigation bar with custom glyphs.
- Loading/waiting uses an animated floating Aurora loader with pulsing icon, shimmer copy, progress percentage and cyan progress bar. The app also has reusable loading, empty, error and offline states.
- Motion respects Android's system reduced-animation setting in its reusable live indicator and hero components.

## Implemented in Gateway source
- Imported the original FieldPulseUi.kt Aurora component system into a Gateway-owned package.
- Imported reusable loading/empty/error/offline state components.
- Added GatewayAuroraTheme with the main app's light/dark Material 3 color schemes and rounded shape scale.
- Enabled Jetpack Compose in the Gateway app module and added the Compose compiler plugin/dependencies.
- Replaced the bare programmatic LinearLayout diagnostic screen with a Compose-based Gateway shell: Aurora hero, metric cards, status surfaces, gradient/outlined buttons, controller setup card and four-section bottom navigation.
- Kept Google/Firebase sign-in, secure token verification and authenticated controller test callbacks in the Activity layer.
- All changes are isolated to devmonitor-5.0-gateway-android-download; the Full edition and its business logic are not modified.

## Second visual-parity implementation pass
- Added the Aurora modal navigation drawer with branded header, authenticated-state pill and four workspace destinations.
- Added animated fade/slide transitions between Gateway sections.
- Added the floating Aurora wait panel with pulsing mark, indeterminate cyan sweep rail and spinner; it does not display fabricated percentage progress.
- Exposed the main app's reduced-motion preference helper to the Gateway loading component.
- Reused the original brand mark in both toolbar and drawer.

## Still required before claiming visual parity
- [x] Compose compilation issue identified from CI and corrected (missing animation/graphics imports); awaiting rerun.
- [x] Original DevMonitor brand icon applied to the Gateway launcher manifest.
- [x] Branded modal drawer, four destinations, animated screen transitions and floating Aurora waiting overlay implemented.
- [x] Reusable Aurora buttons, cards, hero, metrics, status pills and loading/empty/error/offline states are available in the Gateway package.
- [ ] Compare screenshots at matching phone dimensions; check spacing, typography, card elevation, button dimensions, responsive layouts and animation timing on a real device.
- [ ] Test light/dark behavior and reduced-motion accessibility on device.
- [ ] Compare dialog and detailed workflow interactions as those Gateway screens are implemented; refine visual details continuously during development.
- [ ] Record any intentional Gateway-only differences.

## Acceptance gate
Treat the current Aurora implementation as the shared visual baseline, not a claim of pixel-perfect completion. The remaining parity gate is device screenshot/interaction QA at matching dimensions, accessibility checks and iterative comparison of dialogs and future workflows. Refine those details alongside feature development; do not block the Gateway engineering sequence on cosmetic micro-tuning.

## Source reference
The reference archive is v337 from the development branch. It is a reference source only; no Full-edition code or business logic has been merged into the Gateway app.
