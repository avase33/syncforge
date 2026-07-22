// The client runs the *same* CRDT as the server. That is the whole point: the
// browser applies its own edits optimistically and merges everyone else's ops
// with identical last-write-wins rules, so what you see locally is exactly what
// the server converges to — no round trip required before a shape moves.

export type Scalar = number | string | boolean | null;

/** A last-write-wins register: a value plus its Lamport stamp (counter c, replica r). */
export interface Reg {
  v: Scalar;
  c: number;
  r: string;
}

export type ShapeFields = Record<string, Reg>;
export type Snapshot = { shapes: Record<string, { fields: ShapeFields }> };

/** True if stamp a strictly dominates stamp b, under the same total order the server uses. */
export function dominates(a: Reg, b: Reg): boolean {
  return a.c > b.c || (a.c === b.c && a.r > b.r);
}

const MUTABLE_FIELDS = ["type", "x", "y", "w", "h", "color", "z", "deleted"] as const;

export interface Shape {
  id: string;
  type: string;
  x: number;
  y: number;
  w: number;
  h: number;
  color: string;
  z: number;
  deleted: boolean;
}

/**
 * The client's replica of the board plus its Lamport clock. Local edits call
 * {@link set} to stamp and apply optimistically and return an op frame to send;
 * remote frames go through {@link applyOp} / {@link applySnapshot}.
 */
export class ClientBoard {
  private shapes = new Map<string, ShapeFields>();
  private counter = 0;

  constructor(private site: string) {}

  setSite(site: string): void {
    this.site = site;
  }

  /** Advance the clock past a stamp we have observed. */
  private witness(c: number): void {
    if (c > this.counter) this.counter = c;
  }

  private tick(): number {
    return ++this.counter;
  }

  private mergeReg(fields: ShapeFields, name: string, incoming: Reg): boolean {
    const current = fields[name];
    this.witness(incoming.c);
    if (!current || dominates(incoming, current)) {
      fields[name] = incoming;
      return true;
    }
    return false;
  }

  /** Replace state with a snapshot from the server. */
  applySnapshot(snap: Snapshot): void {
    this.shapes.clear();
    this.counter = 0;
    for (const [id, body] of Object.entries(snap.shapes ?? {})) {
      const fields: ShapeFields = {};
      for (const [name, reg] of Object.entries(body.fields)) {
        fields[name] = reg;
        this.witness(reg.c);
      }
      this.shapes.set(id, fields);
    }
  }

  /** Merge a remote op frame. Returns true if anything actually changed. */
  applyOp(frame: { id: string; fields: ShapeFields }): boolean {
    let fields = this.shapes.get(frame.id);
    if (!fields) {
      fields = {};
      this.shapes.set(frame.id, fields);
    }
    let changed = false;
    for (const [name, reg] of Object.entries(frame.fields)) {
      if (this.mergeReg(fields, name, reg)) changed = true;
    }
    return changed;
  }

  /**
   * Apply a local edit: stamp each changed field, merge it in, and return the
   * op frame to broadcast. Applying locally first is what makes dragging feel
   * instant.
   */
  set(id: string, changes: Partial<Record<(typeof MUTABLE_FIELDS)[number], Scalar>>): {
    t: "op";
    id: string;
    fields: ShapeFields;
  } {
    let fields = this.shapes.get(id);
    if (!fields) {
      fields = {};
      this.shapes.set(id, fields);
    }
    const opFields: ShapeFields = {};
    for (const [name, value] of Object.entries(changes)) {
      const reg: Reg = { v: value as Scalar, c: this.tick(), r: this.site };
      fields[name] = reg;
      opFields[name] = reg;
    }
    return { t: "op", id, fields: opFields };
  }

  private num(fields: ShapeFields, name: string, fallback: number): number {
    const v = fields[name]?.v;
    return typeof v === "number" ? v : fallback;
  }

  private str(fields: ShapeFields, name: string, fallback: string): string {
    const v = fields[name]?.v;
    return typeof v === "string" ? v : fallback;
  }

  /** The live, non-deleted shapes, sorted by z then id for stable paint order. */
  visibleShapes(): Shape[] {
    const out: Shape[] = [];
    for (const [id, fields] of this.shapes) {
      if (fields["deleted"]?.v === true) continue;
      out.push({
        id,
        type: this.str(fields, "type", "rect"),
        x: this.num(fields, "x", 0),
        y: this.num(fields, "y", 0),
        w: this.num(fields, "w", 120),
        h: this.num(fields, "h", 80),
        color: this.str(fields, "color", "#3b82f6"),
        z: this.num(fields, "z", 1),
        deleted: false,
      });
    }
    out.sort((a, b) => (a.z - b.z) || a.id.localeCompare(b.id));
    return out;
  }
}
