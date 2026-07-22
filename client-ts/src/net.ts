import { ClientBoard, type ShapeFields, type Snapshot } from "./crdt";

export interface Cursor {
  site: string;
  x: number;
  y: number;
  color: string;
}

type ServerFrame =
  | { t: "hello"; site: string; instance: string; room: string }
  | { t: "snapshot"; site: string; board: Snapshot }
  | { t: "op"; id: string; fields: ShapeFields }
  | { t: "cursor"; site: string; x: number; y: number; color: string }
  | { t: "leave"; site: string }
  | { t: "pong" };

/**
 * The WebSocket half of the client. It owns the socket, feeds inbound frames
 * into the {@link ClientBoard}, and exposes a tiny callback surface the canvas
 * subscribes to. Reconnects automatically — because state is a CRDT, a dropped
 * connection just means the next snapshot re-converges us, with no lost edits
 * that were already acknowledged.
 */
export class SyncClient {
  private ws?: WebSocket;
  site: string;
  readonly cursors = new Map<string, Cursor>();

  onReady: () => void = () => {};
  onChange: () => void = () => {};
  onStatus: (s: string) => void = () => {};

  constructor(
    private board: ClientBoard,
    private room: string,
    preferredSite: string,
  ) {
    this.site = preferredSite;
  }

  connect(): void {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const url =
      `${proto}://${location.host}/rooms/${encodeURIComponent(this.room)}` +
      `?site=${encodeURIComponent(this.site)}`;
    const ws = new WebSocket(url);
    this.ws = ws;
    ws.onopen = () => this.onStatus("connected");
    ws.onclose = () => {
      this.onStatus("disconnected — retrying");
      setTimeout(() => this.connect(), 1000);
    };
    ws.onmessage = (ev) => this.handle(JSON.parse(ev.data as string) as ServerFrame);
  }

  private handle(msg: ServerFrame): void {
    switch (msg.t) {
      case "hello":
        this.site = msg.site;
        this.board.setSite(msg.site);
        this.onReady();
        break;
      case "snapshot":
        this.board.applySnapshot(msg.board);
        this.onChange();
        break;
      case "op":
        if (this.board.applyOp(msg)) this.onChange();
        break;
      case "cursor":
        this.cursors.set(msg.site, { site: msg.site, x: msg.x, y: msg.y, color: msg.color });
        this.onChange();
        break;
      case "leave":
        this.cursors.delete(msg.site);
        this.onChange();
        break;
      case "pong":
        break;
    }
  }

  send(frame: unknown): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(frame));
    }
  }

  sendCursor(x: number, y: number, color: string): void {
    this.send({ t: "cursor", site: this.site, x, y, color });
  }
}
