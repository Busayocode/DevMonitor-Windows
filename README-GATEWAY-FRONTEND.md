# DevMonitor Gateway 5.0 — AI Studio frontend

This branch adds a small React/Vite frontend so Google AI Studio can install Node dependencies and start a preview. It does not replace or rewrite the Python controller.

## Run

- Node.js 20 or newer
- `npm install`
- `npm run dev`

## Important integration boundary

The interface intentionally shows **Controller not connected** until a real HTTPS controller URL and authenticated API integration are configured. It does not fabricate gateway registrations, job counts, SMS attempts, or successful operations. Cancel is disabled in preview mode.

The controller source remains under `controller/service/` and its PostgreSQL migrations remain under `controller/postgres/`. This frontend does not provision hosting, a database, credentials, or an endpoint.

## SMS safety

Outbound SMS dispatch remains disabled until the backend is deployed and real carrier tests are completed. The backend's exact-attempt cancellation and four-minute post-submission cooldown rules remain authoritative. A valid accepted reply does not add a post-reply quiet period.

## Build

`npm run build` produces the static frontend in `dist/`.
