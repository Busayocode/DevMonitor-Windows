# DevMonitor 5.0 Gateway — Visual Parity Standard

## Release requirement
The Gateway must not be released as a bare prototype. Its Android UI must reuse the established DevMonitor visual language and interaction patterns as closely as the separate Gateway edition permits.

## Required parity areas
- App theme: palette, surfaces, typography, spacing, shapes, elevation and icon style.
- Navigation: matching top-level navigation, toolbar behavior, back behavior, selected states and submenu patterns where applicable.
- Controls: button hierarchy, sizing, corner treatment, touch feedback, text fields, dialogs, cards and empty states.
- Motion: screen transitions, button feedback, progress indicators, loading/waiting animations, completion and error states.
- Accessibility: touch targets, contrast, dynamic text sizing and reduced-motion behavior.
- Responsive behavior: preserve the main app's phone-first density and compact layouts across larger screens.

## Implementation approach
1. Inspect the latest available Android source archive from the development branch and extract the actual UI/theme resources and reusable components as a reference.
2. Record a design-token map and component inventory before building Gateway screens.
3. Port the visual system into Gateway-owned UI modules without importing Full-edition SMS permissions or changing Full-edition business logic.
4. Build Gateway screens using the shared components rather than default platform widgets.
5. Compare screenshots at matching device dimensions and iterate on visual differences before asking the user to install a build.

## Current audit
The current Gateway `MainActivity` is a plain programmatic `LinearLayout` with default Android `Button`, `EditText` and `TextView` controls. It is an authentication diagnostic screen, not release-quality UI. It does not yet meet visual parity.

The current Gateway branch does not contain the full main-app Android source tree; the development branch stores the Android source as ZIP archives. A short-lived CI retrieval workflow is used to obtain the v337 reference archive internally for inspection. This workflow does not alter the Full app or the development branch.

## Acceptance gate
Do not call visual parity complete based on theme colours alone. Navigation structure, control styling, motion, loading/waiting states, dialogs, empty/error/success states and responsive layout must all be checked against the reference app. Record any intentional Gateway-only differences.
