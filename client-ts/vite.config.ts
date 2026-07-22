import { defineConfig } from "vite";

// The client talks to the syncforge server over WebSocket at :8080. In dev we
// proxy /rooms and /api so the browser only ever sees one origin.
export default defineConfig({
  server: {
    port: 3000,
    proxy: {
      "/rooms": { target: "ws://localhost:8080", ws: true },
      "/api": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
});
