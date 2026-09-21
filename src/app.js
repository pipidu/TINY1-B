import express from "express";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(__dirname, "..", "public");

export function greet(name) {
  const trimmed = typeof name === "string" ? name.trim() : "";
  const who = trimmed.length > 0 ? trimmed : "world";
  return `Hello, ${who}! Welcome to TINY1-B.`;
}

export function createApp() {
  const app = express();

  app.get("/api/health", (_req, res) => {
    res.json({ status: "ok", service: "tiny1-b", uptime: process.uptime() });
  });

  app.get("/api/greeting", (req, res) => {
    const name = typeof req.query.name === "string" ? req.query.name : "";
    res.json({ message: greet(name) });
  });

  app.use(express.static(publicDir));

  return app;
}
