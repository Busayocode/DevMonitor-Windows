# DevMonitor receipt-style share-card trial

This branch is intentionally isolated from `main`.

## Baselines and trial versions
- Android baseline: `DevMonitor-An-v337-4.7.0-TARGET-KOTLIN-RECHECK-CONTINUE-FIX.zip`
- Android trial: v338, versionCode 338
- Windows baseline: `DevMonitor-PC-v328-4.7.0-TARGET-RESTORE-ORIGINAL-DISPLAY-SCALE.zip`
- Windows trial: v329, assembly version 4.7.0.329

The compressed unified diffs in this folder are applied after extracting the baseline source ZIPs. This keeps the existing ZIP-based main-branch build workflow and source packages untouched.

## Build
The dedicated workflow `Build DevMonitor Receipt Share Card Trial` builds an Android debug APK and a Windows self-contained trial application. It does not sign or publish a production release.

## Scope
The trial changes only share-card presentation and export:
- receipt-inspired earnings progression layout on Android and Windows;
- Android PNG export remains available;
- Android PDF export draws the same layout directly to a PDF canvas;
- PC-generated cards retain the existing PNG handoff to Android.

Payment calculation, cycle, source selection, MOM, FRC, Sunday bonus, and sync rules are out of scope and must remain unchanged.
