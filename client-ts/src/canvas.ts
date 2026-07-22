import { type ClientBoard, type Shape } from "./crdt";
import { type SyncClient } from "./net";

const PALETTE = ["#ef4444", "#3b82f6", "#22c55e", "#eab308", "#a855f7", "#ec4899"];

/**
 * Renders the board and turns pointer gestures into CRDT edits. Every gesture
 * applies to the local board first (so it is instant) and sends the resulting
 * op; the same op comes back to no one — the server relays it to the other
 * tabs, which merge it. Drag one tab, watch the other follow.
 */
export class CanvasView {
  private ctx: CanvasRenderingContext2D;
  private dragId: string | null = null;
  private dragDX = 0;
  private dragDY = 0;
  private readonly myColor = PALETTE[Math.floor(Math.random() * PALETTE.length)];
  private lastCursorSent = 0;

  constructor(
    private canvas: HTMLCanvasElement,
    private board: ClientBoard,
    private net: SyncClient,
  ) {
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("2d canvas context unavailable");
    this.ctx = ctx;

    this.resize();
    window.addEventListener("resize", () => {
      this.resize();
      this.render();
    });
    canvas.addEventListener("mousedown", (e) => this.onDown(e));
    window.addEventListener("mousemove", (e) => this.onMove(e));
    window.addEventListener("mouseup", () => {
      this.dragId = null;
    });
    canvas.addEventListener("dblclick", (e) => this.onCreate(e));
    net.onChange = () => this.render();
  }

  private resize(): void {
    const dpr = window.devicePixelRatio || 1;
    const rect = this.canvas.getBoundingClientRect();
    this.canvas.width = rect.width * dpr;
    this.canvas.height = rect.height * dpr;
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  private pos(e: MouseEvent): { x: number; y: number } {
    const r = this.canvas.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }

  private hit(x: number, y: number): Shape | null {
    const shapes = this.board.visibleShapes();
    for (let i = shapes.length - 1; i >= 0; i--) {
      const s = shapes[i];
      if (x >= s.x && x <= s.x + s.w && y >= s.y && y <= s.y + s.h) return s;
    }
    return null;
  }

  private onDown(e: MouseEvent): void {
    const p = this.pos(e);
    const s = this.hit(p.x, p.y);
    if (s) {
      this.dragId = s.id;
      this.dragDX = p.x - s.x;
      this.dragDY = p.y - s.y;
    }
  }

  private onMove(e: MouseEvent): void {
    const p = this.pos(e);
    const now = performance.now();
    if (now - this.lastCursorSent > 40) {
      this.net.sendCursor(p.x, p.y, this.myColor);
      this.lastCursorSent = now;
    }
    if (this.dragId) {
      const frame = this.board.set(this.dragId, { x: p.x - this.dragDX, y: p.y - this.dragDY });
      this.net.send(frame);
      this.render();
    }
  }

  private onCreate(e: MouseEvent): void {
    const p = this.pos(e);
    const id = `s-${this.net.site}-${Date.now().toString(36)}`;
    const frame = this.board.set(id, {
      type: "rect",
      x: p.x - 70,
      y: p.y - 45,
      w: 140,
      h: 90,
      color: this.myColor,
      z: 1,
      deleted: false,
    });
    this.net.send(frame);
    this.render();
  }

  render(): void {
    const ctx = this.ctx;
    const r = this.canvas.getBoundingClientRect();
    ctx.clearRect(0, 0, r.width, r.height);

    ctx.strokeStyle = "#111827";
    ctx.lineWidth = 1;
    for (let gx = 0; gx < r.width; gx += 40) {
      ctx.beginPath();
      ctx.moveTo(gx, 0);
      ctx.lineTo(gx, r.height);
      ctx.stroke();
    }
    for (let gy = 0; gy < r.height; gy += 40) {
      ctx.beginPath();
      ctx.moveTo(0, gy);
      ctx.lineTo(r.width, gy);
      ctx.stroke();
    }

    for (const s of this.board.visibleShapes()) this.paintShape(s);
    for (const c of this.net.cursors.values()) this.paintCursor(c.x, c.y, c.color, c.site);
  }

  private paintShape(s: Shape): void {
    const ctx = this.ctx;
    ctx.fillStyle = s.color + "cc";
    ctx.strokeStyle = s.color;
    ctx.lineWidth = 2;
    if (s.type === "ellipse") {
      ctx.beginPath();
      ctx.ellipse(s.x + s.w / 2, s.y + s.h / 2, s.w / 2, s.h / 2, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
    } else if (s.type === "line") {
      ctx.beginPath();
      ctx.moveTo(s.x, s.y);
      ctx.lineTo(s.x + s.w, s.y + s.h);
      ctx.stroke();
    } else {
      this.roundedRect(s.x, s.y, s.w, s.h, 8);
      ctx.fill();
      ctx.stroke();
    }
  }

  /** Manual rounded rectangle so we do not depend on Canvas.roundRect typings. */
  private roundedRect(x: number, y: number, w: number, h: number, radius: number): void {
    const ctx = this.ctx;
    const rad = Math.min(radius, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + rad, y);
    ctx.arcTo(x + w, y, x + w, y + h, rad);
    ctx.arcTo(x + w, y + h, x, y + h, rad);
    ctx.arcTo(x, y + h, x, y, rad);
    ctx.arcTo(x, y, x + w, y, rad);
    ctx.closePath();
  }

  private paintCursor(x: number, y: number, color: string, site: string): void {
    const ctx = this.ctx;
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.moveTo(x, y);
    ctx.lineTo(x + 12, y + 4);
    ctx.lineTo(x + 4, y + 12);
    ctx.closePath();
    ctx.fill();
    ctx.font = "11px ui-sans-serif, system-ui, sans-serif";
    ctx.fillText(site, x + 14, y + 14);
  }
}
