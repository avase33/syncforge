import { ClientBoard } from "./crdt";
import { SyncClient } from "./net";
import { CanvasView } from "./canvas";

// Room and preferred site come from the URL, so opening ?room=demo in two tabs
// (or two browsers) drops you into the same board.
const params = new URLSearchParams(location.search);
const room = params.get("room") ?? "demo";
const site = params.get("site") ?? `u-${Math.random().toString(36).slice(2, 7)}`;

const statusEl = document.getElementById("status") as HTMLElement;
const roomEl = document.getElementById("room") as HTMLElement;
roomEl.textContent = `room: ${room}`;

const board = new ClientBoard(site);
const net = new SyncClient(board, room, site);
const canvas = document.getElementById("stage") as HTMLCanvasElement;
const view = new CanvasView(canvas, board, net);

net.onStatus = (s) => {
  statusEl.textContent = s;
};
net.onReady = () => {
  statusEl.textContent = `connected as ${net.site}`;
  view.render();
};

net.connect();
view.render();
