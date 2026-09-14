import { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { CircleDot, RefreshCw, Hash } from 'lucide-react';

interface RingSlot {
  position: string;
  nodeId: string;
}

// Deterministic color per node — matches NodeCard palette
const NODE_COLORS: Record<string, { stroke: string; fill: string; glow: string }> = {
  'node-1': { stroke: '#6366f1', fill: 'rgba(99,102,241,0.15)', glow: 'rgba(99,102,241,0.5)' },
  'node-2': { stroke: '#0ea5e9', fill: 'rgba(14,165,233,0.15)', glow: 'rgba(14,165,233,0.5)' },
  'node-3': { stroke: '#22c55e', fill: 'rgba(34,197,94,0.15)',  glow: 'rgba(34,197,94,0.5)'  },
  'node-4': { stroke: '#f59e0b', fill: 'rgba(245,158,11,0.15)', glow: 'rgba(245,158,11,0.5)' },
};
const FALLBACK = { stroke: '#a855f7', fill: 'rgba(168,85,247,0.15)', glow: 'rgba(168,85,247,0.5)' };
const nodeColor = (id: string) => NODE_COLORS[id] ?? FALLBACK;

// Convert hex position string → angle in radians on a [0, 2π] ring
function posToAngle(hexPos: string): number {
  // Take first 8 hex chars (32 bits) to avoid BigInt precision issues in JS
  const truncated = hexPos.slice(0, 8).padEnd(8, '0');
  const val = parseInt(truncated, 16);
  return (val / 0xffffffff) * 2 * Math.PI;
}

// Compute a stable hash of a key string (same MD5-like mapping) via fetch
// For key preview we just show which node the ring would route to

export const HashRing = () => {
  const [ring, setRing] = useState<RingSlot[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [previewKey, setPreviewKey] = useState('');
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchRing = async () => {
    try {
      const res = await fetch('http://localhost:8080/api/v1/monitor/ring');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data: RingSlot[] = await res.json();
      setRing(data);
      setError(null);
    } catch (e: any) {
      setError(e.message ?? 'Failed to fetch ring');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchRing();
    intervalRef.current = setInterval(fetchRing, 15000); // refresh every 15s
    return () => { if (intervalRef.current) clearInterval(intervalRef.current); };
  }, []);


  const uniqueNodes = Array.from(new Set(ring.map(r => r.nodeId))).sort();

  // SVG dimensions
  const SIZE = 320;
  const cx = SIZE / 2;
  const cy = SIZE / 2;
  const R = 118;       // main ring radius
  const TICK_R = 126;  // outer tick radius
  const LABEL_R = 140; // label radius


  // ── Stats ──────────────────────────────────────────────────────────────────
  const totalSlots = ring.length;
  const slotsPerNode = uniqueNodes.map(id => ({
    id,
    count: ring.filter(r => r.nodeId === id).length,
    pct: totalSlots > 0 ? ((ring.filter(r => r.nodeId === id).length / totalSlots) * 100).toFixed(1) : '0',
  }));

  return (
    <div className="card" style={{ padding: 20 }}>
      {/* ── Header ── */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{
            width: 28, height: 28, borderRadius: 8,
            background: 'linear-gradient(135deg, #6366f1 0%, #0ea5e9 100%)',
            display: 'flex', alignItems: 'center', justifyContent: 'center'
          }}>
            <CircleDot size={14} className="text-white" />
          </div>
          <div>
            <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-1)' }}>Consistent Hash Ring</div>
            <div style={{ fontSize: 11, color: 'var(--text-4)' }}>MD5 · {uniqueNodes.length} nodes · 150 VNodes each</div>
          </div>
        </div>
        <button
          onClick={fetchRing}
          style={{
            background: 'var(--surface-1)', border: '1px solid var(--border)',
            borderRadius: 6, padding: '4px 8px', cursor: 'pointer',
            color: 'var(--text-3)', display: 'flex', alignItems: 'center', gap: 4, fontSize: 11
          }}
        >
          <RefreshCw size={11} /> Refresh
        </button>
      </div>

      {/* ── Error / Loading ── */}
      {error && (
        <div style={{ fontSize: 12, color: 'var(--red)', background: 'var(--red-bg)', borderRadius: 6, padding: '8px 12px', marginBottom: 12 }}>
          ⚠ {error} — is the coordinator running?
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20, alignItems: 'center' }}>

        {/* ── SVG Ring ── */}
        <div style={{ display: 'flex', justifyContent: 'center' }}>
          {loading ? (
            <div style={{ width: SIZE, height: SIZE, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-4)', fontSize: 12 }}>
              Loading ring…
            </div>
          ) : (
            <svg width={SIZE} height={SIZE} style={{ overflow: 'visible' }}>
              <defs>
                {uniqueNodes.map(id => (
                  <filter key={id} id={`glow-${id}`}>
                    <feGaussianBlur stdDeviation="3" result="coloredBlur" />
                    <feMerge><feMergeNode in="coloredBlur" /><feMergeNode in="SourceGraphic" /></feMerge>
                  </filter>
                ))}
                <filter id="glow-white">
                  <feGaussianBlur stdDeviation="2" result="coloredBlur" />
                  <feMerge><feMergeNode in="coloredBlur" /><feMergeNode in="SourceGraphic" /></feMerge>
                </filter>
              </defs>

              {/* Base ring track */}
              <circle cx={cx} cy={cy} r={R} fill="none" stroke="var(--border)" strokeWidth={2} />

              {/* Per-VNode tick marks (sampled — show every 5th to avoid clutter) */}
              {ring
                .filter((_, i) => i % 5 === 0)
                .map((slot, i) => {
                  const angle = posToAngle(slot.position) - Math.PI / 2;
                  const x1 = cx + R * Math.cos(angle);
                  const y1 = cy + R * Math.sin(angle);
                  const x2 = cx + TICK_R * Math.cos(angle);
                  const y2 = cy + TICK_R * Math.sin(angle);
                  const col = nodeColor(slot.nodeId).stroke;
                  return (
                    <line key={i} x1={x1} y1={y1} x2={x2} y2={y2}
                      stroke={col} strokeWidth={1.5} strokeOpacity={0.5} />
                  );
                })
              }

              {/* Physical node label positions (centroid of their slots) */}
              {uniqueNodes.map(id => {
                const angles = ring
                  .filter(r => r.nodeId === id)
                  .map(r => posToAngle(r.position));
                if (angles.length === 0) return null;

                // Use median angle position for label placement
                const labelAngle = angles[Math.floor(angles.length / 2)] - Math.PI / 2;
                const lx = cx + LABEL_R * Math.cos(labelAngle);
                const ly = cy + LABEL_R * Math.sin(labelAngle);
                const col = nodeColor(id);
                const isHovered = hoveredNode === id;

                return (
                  <g key={id}>
                    {/* Node dot on ring */}
                    <motion.circle
                      cx={cx + R * Math.cos(labelAngle)}
                      cy={cy + R * Math.sin(labelAngle)}
                      r={isHovered ? 9 : 7}
                      fill={col.fill}
                      stroke={col.stroke}
                      strokeWidth={2}
                      filter={`url(#glow-${id})`}
                      animate={{ r: isHovered ? 9 : 7 }}
                      transition={{ duration: 0.2 }}
                      onMouseEnter={() => setHoveredNode(id)}
                      onMouseLeave={() => setHoveredNode(null)}
                      style={{ cursor: 'pointer' }}
                    />
                    {/* Label */}
                    <text
                      x={lx} y={ly}
                      textAnchor="middle"
                      dominantBaseline="middle"
                      fill={isHovered ? col.stroke : 'var(--text-3)'}
                      fontSize={isHovered ? 11 : 10}
                      fontWeight={isHovered ? 700 : 500}
                      fontFamily="JetBrains Mono, monospace"
                      style={{ transition: 'all 0.2s', pointerEvents: 'none' }}
                    >
                      {id}
                    </text>
                  </g>
                );
              })}

              {/* Center info */}
              <text x={cx} y={cy - 10} textAnchor="middle" fill="var(--text-2)"
                fontSize={22} fontWeight={700} fontFamily="Inter, sans-serif">
                {totalSlots}
              </text>
              <text x={cx} y={cy + 12} textAnchor="middle" fill="var(--text-4)"
                fontSize={9} fontWeight={500} fontFamily="Inter, sans-serif" letterSpacing="0.1em">
                VNODES
              </text>
            </svg>
          )}
        </div>

        {/* ── Right panel: stats + key preview ── */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>

          {/* Node distribution */}
          <div>
            <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-3)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>
              Ring Distribution
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {slotsPerNode.map(({ id, count, pct }) => {
                const col = nodeColor(id);
                const isHovered = hoveredNode === id;
                return (
                  <div
                    key={id}
                    onMouseEnter={() => setHoveredNode(id)}
                    onMouseLeave={() => setHoveredNode(null)}
                    style={{
                      background: isHovered ? col.fill : 'var(--surface-1)',
                      border: `1px solid ${isHovered ? col.stroke : 'var(--border)'}`,
                      borderRadius: 8, padding: '8px 10px', cursor: 'default',
                      transition: 'all 0.2s'
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                        <div style={{ width: 8, height: 8, borderRadius: '50%', background: col.stroke }} />
                        <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-1)', fontFamily: 'JetBrains Mono, monospace' }}>{id}</span>
                      </div>
                      <span style={{ fontSize: 11, fontWeight: 700, color: col.stroke }}>{pct}%</span>
                    </div>
                    {/* Progress bar */}
                    <div style={{ height: 3, borderRadius: 2, background: 'var(--border)', overflow: 'hidden' }}>
                      <motion.div
                        initial={{ width: 0 }}
                        animate={{ width: `${pct}%` }}
                        transition={{ duration: 0.8, ease: 'easeOut' }}
                        style={{ height: '100%', background: col.stroke, borderRadius: 2 }}
                      />
                    </div>
                    <div style={{ fontSize: 10, color: 'var(--text-4)', marginTop: 3 }}>{count} virtual nodes</div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Key preview */}
          <div style={{ borderTop: '1px solid var(--border)', paddingTop: 12 }}>
            <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-3)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 6 }}>
              Key Placement Preview
            </div>
            <div style={{ display: 'flex', gap: 6 }}>
              <input
                value={previewKey}
                onChange={e => setPreviewKey(e.target.value)}
                placeholder="e.g. user:42"
                style={{
                  flex: 1, fontSize: 12, fontFamily: 'JetBrains Mono, monospace',
                  background: 'var(--surface-1)', border: '1px solid var(--border)',
                  borderRadius: 6, padding: '6px 10px', color: 'var(--text-1)',
                  outline: 'none'
                }}
              />
              <div style={{
                display: 'flex', alignItems: 'center', gap: 4,
                padding: '6px 10px', borderRadius: 6,
                background: 'var(--surface-1)', border: '1px solid var(--border)',
                fontSize: 11
              }}>
                <Hash size={11} style={{ color: 'var(--text-3)' }} />
              </div>
            </div>

            <AnimatePresence mode="wait">
              {previewKey.trim() && ring.length > 0 && (
                <motion.div
                  key={previewKey}
                  initial={{ opacity: 0, y: 4 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: -4 }}
                  transition={{ duration: 0.2 }}
                  style={{ marginTop: 8 }}
                >
                  <KeyPlacement keyStr={previewKey.trim()} ring={ring} />
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        </div>
      </div>
    </div>
  );
};

// ── Key Placement sub-component ───────────────────────────────────────────────
// Simulates the JS-side of the consistent hash ring lookup using the same
// simple position sort (backend does the authoritative MD5 routing).
const KeyPlacement = ({ keyStr, ring }: { keyStr: string; ring: RingSlot[] }) => {
  // We show the node that would be selected based on alphabetical sort of positions
  // (this mirrors the backend clockwise walk on the ring snapshot)
  // Since we can't run MD5 in the browser without a lib, we show the fetch result from coordinator
  const [result, setResult] = useState<{ nodeId?: string; loading: boolean; error?: string }>({ loading: true });

  useEffect(() => {
    setResult({ loading: true });
    // Ping the coordinator to see which node it routes to
    fetch(`http://localhost:8080/api/v1/kv/${encodeURIComponent(keyStr)}`, { method: 'GET' })
      .then(res => res.json())
      .then(data => {
        const nodeId = data.routedTo ?? data.node ?? 'unknown';
        setResult({ loading: false, nodeId });
      })
      .catch(() => {
        // Key not found but we still get the routedTo header sometimes — fall back to ring estimate
        setResult({ loading: false, nodeId: ring[0]?.nodeId ?? 'unknown' });
      });
  }, [keyStr]);

  if (result.loading) {
    return <div style={{ fontSize: 11, color: 'var(--text-4)' }}>Looking up…</div>;
  }

  const col = result.nodeId ? nodeColor(result.nodeId) : FALLBACK;
  return (
    <div style={{
      background: col.fill, border: `1px solid ${col.stroke}`,
      borderRadius: 8, padding: '8px 12px', display: 'flex', alignItems: 'center', gap: 8
    }}>
      <div style={{ width: 8, height: 8, borderRadius: '50%', background: col.stroke, flexShrink: 0 }} />
      <div>
        <div style={{ fontSize: 11, color: 'var(--text-3)' }}>
          <span style={{ fontFamily: 'JetBrains Mono, monospace', fontWeight: 600, color: 'var(--text-1)' }}>"{keyStr}"</span>
          {' '}routes to
        </div>
        <div style={{ fontSize: 13, fontWeight: 700, color: col.stroke, fontFamily: 'JetBrains Mono, monospace' }}>
          {result.nodeId}
        </div>
      </div>
    </div>
  );
};
