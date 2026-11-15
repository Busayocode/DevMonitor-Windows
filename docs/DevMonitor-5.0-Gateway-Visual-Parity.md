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

## Still required before claiming visual parity
- CI validation of the new Compose implementation.
- Copy/use the original DevMonitor app icon and verify the launcher/screen branding.
- Implement the full Gateway navigation drawer, screen transitions, dialogs, and detailed Gateway workflows using the same patterns.
- Port and adapt the main app's floating waiting loader rather than relying only on a standard spinner.
- Compare screenshots at matching phone dimensions; check spacing, typography, card elevation, button dimensions, responsive layouts and animation timing.
- Test light/dark behavior and reduced-motion accessibility.
- Record any intentional Gateway-only differences.

## Acceptance gate
Do not call visual parity complete based on theme colours or a single dashboard screen. Navigation structure, control styling, motion, loading/waiting states, dialogs, empty/error/success states and responsive layout must all be checked against the reference app. Record any intentional Gateway-only differences.

## Source reference
The reference archive is v337 from the development branch. It is a reference source only; no Full-edition code or business logic has been merged into the Gateway app.
