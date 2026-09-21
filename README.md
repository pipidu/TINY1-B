# TINY1-B

A tiny starter web app: an Express JSON API with a static browser frontend.

## Requirements

- Node.js >= 20 (developed against Node 22)

## Getting started

```bash
npm install
npm run dev     # start with auto-reload on http://localhost:3000
# or
npm start       # start without watch
```

Then open http://localhost:3000.

## API

- `GET /api/health` — service health, e.g. `{ "status": "ok", "service": "tiny1-b" }`
- `GET /api/greeting?name=<name>` — returns `{ "message": "Hello, <name>! Welcome to TINY1-B." }`

## Testing

```bash
npm test        # runs the built-in Node test runner (node --test)
```

## Project layout

```
src/app.js        Express app factory + greet() helper
src/server.js     HTTP server entrypoint
public/           Static frontend (HTML/CSS/JS)
test/app.test.js  Unit + API tests
```
