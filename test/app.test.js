import test from "node:test";
import assert from "node:assert/strict";
import { greet, createApp } from "../src/app.js";

test("greet uses provided name", () => {
  assert.equal(greet("hy"), "Hello, hy! Welcome to TINY1-B.");
});

test("greet falls back to world for empty input", () => {
  assert.equal(greet("   "), "Hello, world! Welcome to TINY1-B.");
  assert.equal(greet(undefined), "Hello, world! Welcome to TINY1-B.");
});

test("GET /api/health returns ok", async () => {
  const server = createApp().listen(0);
  try {
    const { port } = server.address();
    const res = await fetch(`http://127.0.0.1:${port}/api/health`);
    const body = await res.json();
    assert.equal(res.status, 200);
    assert.equal(body.status, "ok");
    assert.equal(body.service, "tiny1-b");
  } finally {
    server.close();
  }
});

test("GET /api/greeting returns a personalized message", async () => {
  const server = createApp().listen(0);
  try {
    const { port } = server.address();
    const res = await fetch(`http://127.0.0.1:${port}/api/greeting?name=hy`);
    const body = await res.json();
    assert.equal(res.status, 200);
    assert.equal(body.message, "Hello, hy! Welcome to TINY1-B.");
  } finally {
    server.close();
  }
});
