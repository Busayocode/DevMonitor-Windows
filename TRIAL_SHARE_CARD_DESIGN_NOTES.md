# DevMonitor — Receipt-style share-card trial

Branch: `trial/receipt-style-share-cards`

## Why this trial exists
The current share cards have technically high-resolution output but still feel more like promotional flyers than trustworthy financial statements. The trial borrows the disciplined document hierarchy of fintech transaction receipts: clear identity, source and period, one prominent amount with an explicit estimate label, concise figures, a ledger-style breakdown, and a restrained footer.

OPay's internal rendering engine is not publicly documented. This trial does not attempt to copy its implementation; it adopts the general approach of rendering structured data into a purpose-built share document.

## Itemized changes
1. Removed the stock photographic hero from the earnings progression card; this avoids baked-in sample payment wording and gives the real data more space.
2. Replaced decorative flyer composition with a restrained receipt/report layout, consistent typography, aligned values, clearer section hierarchy, and lighter separators.
3. Kept Canvas-based Android drawing and WPF vector drawing for sharp text and shapes.
4. Kept Android PNG export at 1.5× logical scale to balance legibility and memory consumption.
5. Added an Android PDF path using `PdfDocument`; the same layout is drawn directly to the PDF canvas rather than embedding a screenshot of the PNG.
6. Added a separate PDF share action for an individual Android earnings card. Existing batch card sharing and PC-to-phone card handoff remain PNG.
7. Updated the PC PNG renderer to the same visual system while preserving its existing source figures and estimate object.

## Protected logic
No intended changes to PaymentRules, PaymentEngine, payment cycle boundaries, Loaded Report / Device Monitor source separation, FRC treatment, Sunday bonus qualification, next-tier estimate inputs, MOM storage, or sync protocol. Rendering only presents values already selected/calculated by existing code.

## Trial verification status
- Baseline ZIPs extracted successfully.
- Android and Windows unified patches apply cleanly to the exact baseline ZIPs.
- Android debug build: pending dedicated GitHub Actions run.
- Windows publish: pending dedicated GitHub Actions run.
- Visual review on a physical Android phone and Windows display: pending.
- PDF viewer/print review: pending.

Do not merge this branch into `main` until the build artifacts are checked and the actual PNG/PDF output has been visually reviewed.
