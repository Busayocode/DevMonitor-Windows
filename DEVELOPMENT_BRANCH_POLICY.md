# DevMonitor Development Branch Policy

## Branch roles

- `main` is the stable reference line. Routine development must not be committed directly to it.
- `development` is the permanent working branch for ongoing Android and Windows development.
- `trial/*` branches are temporary, isolated experiments. A trial is not approved merely because it exists or builds.

## Working rules

1. Start routine feature work and fixes from `development`, and commit the work to `development`.
2. Use a `trial/*` branch for substantial experiments or redesigns that need isolated evaluation.
3. Review and build both Android and Windows where the change affects both platforms. A successful build on one platform does not imply success on the other.
4. Bring a trial into `development` only after its changes have been reviewed and explicitly approved.
5. Do not merge `development` into `main` automatically. Updating `main` requires explicit user approval.
6. Keep cross-platform tracking and business-logic constraints intact. Do not change finalized payment calculations without explicit approval.
7. Build from `development` for ongoing development validation. Use GitHub Actions workflow_dispatch with `development` selected, or push a source ZIP to that branch.

## Current receipt-card trial

The branch `trial/receipt-style-share-cards` remains separate from `development` pending resolution of its Windows build failure and review of both platform outputs. Its Android build passed; its Windows build failed in the recorded trial run. Do not treat the trial as merged or approved.
