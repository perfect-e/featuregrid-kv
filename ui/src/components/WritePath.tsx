import React, { useEffect, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { ClusterEvent, NodeState } from '../hooks/useClusterStream';

interface WritePathProps {
  events: ClusterEvent[];
  nodes: NodeState[];
}

const NODE_COLORS: Record<string, string> = {
  'node-1': '#10b981', // emerald
  'node-2': '#3b82f6', // blue
  'node-3': '#8b5cf6', // purple
};

const OP_COLORS: Record<string, string> = {
  PUT:    'var(--green)',
  GET:    'var(--blue)',
  DELETE: 'var(--red)',
};

export const WritePath: React.FC<WritePathProps> = ({ events, nodes }) => {
  const [active, setActive] = useState<{ nodeId: string; op: string; key: string; success: boolean; id: string } | null>(null);
  const [flushNode, setFlushNode] = useState<string | null>(null);
  const [compactNode, setCompactNode] = useState<string | null>(null);
  const prevId = useRef('');

  useEffect(() => {
    if (!events.length) return;
    const ev = events[0];
    if (ev.id === prevId.current) return;
    prevId.current = ev.id;

    if (ev.type === 'OPERATION') {
      setActive({ nodeId: ev.nodeId, op: ev.op, key: ev.key, success: ev.success, id: ev.id });
      setTimeout(() => setActive(null), 2200);
    }
    if (ev.type === 'SST_FLUSH') {
      setFlushNode(ev.nodeId);
      setTimeout(() => setFlushNode(null), 2200);
    }
    if (ev.type === 'COMPACTION') {
      setCompactNode(ev.nodeId);
      setTimeout(() => setCompactNode(null), 2200);
    }
  }, [events]);

  const opColor = active ? (OP_COLORS[active.op] ?? 'var(--text-3)') : 'var(--text-3)';
  const nodeColors = Object.fromEntries(
    nodes.map(n => [n.id, NODE_COLORS[n.id] ?? 'var(--text-3)'])
  );

  return (
    <div style={{
      background: 'var(--surface-0)',
      border: '1px solid var(--border)',
      borderRadius: 12,
      overflow: 'hidden',
    }}>
      {/* Header */}
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '10px 16px',
        borderBottom: '1px solid var(--border-subtle)',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--text-2)', letterSpacing: '0.06em', textTransform: 'uppercase' }}>
            Data Flow
          </span>
          <span style={{ fontSize: 10, color: 'var(--text-3)' }}>
            write + read path
          </span>
        </div>

        {/* Active operation banner */}
        <AnimatePresence>
          {active && (
            <motion.div
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.9 }}
              style={{
                display: 'flex', alignItems: 'center', gap: 6,
                background: `var(--blue-bg)`,
                border: `1px solid var(--border)`,
                borderRadius: 6, padding: '4px 12px',
                fontSize: 10, fontWeight: 700, fontFamily: 'var(--font-mono)',
                color: opColor,
              }}
            >
              <span style={{
                width: 6, height: 6, borderRadius: '50%',
                background: opColor, display: 'inline-block',
                boxShadow: `0 0 6px ${opColor}`,
              }} />
              {active.op} {active.key.length > 16 ? active.key.slice(0, 16) + '…' : active.key}
              <span style={{ color: 'var(--text-3)', fontWeight: 400 }}>→ {active.nodeId}</span>
            </motion.div>
          )}
        </AnimatePresence>

        {/* Legend */}
        <div style={{ display: 'flex', gap: 12 }}>
          {Object.entries(OP_COLORS).map(([op, color]) => (
            <div key={op} style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 9, color: 'var(--text-3)', fontFamily: 'var(--font-mono)' }}>
              <div style={{ width: 6, height: 6, borderRadius: '50%', background: color }} />
              {op}
            </div>
          ))}
        </div>
      </div>

      {/* SVG Diagram */}
      <svg viewBox="0 0 900 180" style={{ width: '100%', height: 180, display: 'block' }}>
        <defs>
          {/* Arrow marker */}
          <marker id="arr" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
            <path d="M0,0 L0,6 L8,3 z" fill="var(--border)" />
          </marker>
          <marker id="arr-active" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
            <path d="M0,0 L0,6 L8,3 z" fill={opColor} />
          </marker>
          {/* Glow filter */}
          <filter id="glow">
            <feGaussianBlur stdDeviation="3" result="blur" />
            <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
          </filter>
        </defs>

        {/* ── Background swim-lane bands ── */}
        {/* Client lane */}
        <rect x="0" y="0" width="110" height="180" fill="transparent" />
        <text x="55" y="170" textAnchor="middle" fontSize="8" fill="var(--text-3)" fontFamily="var(--font-mono)" letterSpacing="1">CLIENT</text>

        {/* Coordinator lane */}
        <rect x="110" y="0" width="140" height="180" fill="transparent" />
        <text x="180" y="170" textAnchor="middle" fontSize="8" fill="var(--text-3)" fontFamily="var(--font-mono)" letterSpacing="1">COORDINATOR</text>

        {/* Node lane */}
        <rect x="250" y="0" width="140" height="180" fill="transparent" />
        <text x="320" y="170" textAnchor="middle" fontSize="8" fill="var(--text-3)" fontFamily="var(--font-mono)" letterSpacing="1">NODES</text>

        {/* Storage lane */}
        <rect x="390" y="0" width="510" height="180" fill="transparent" />
        <text x="645" y="170" textAnchor="middle" fontSize="8" fill="var(--text-3)" fontFamily="var(--font-mono)" letterSpacing="1">STORAGE ENGINE</text>

        {/* Vertical lane separators */}
        {[110, 250, 390].map(x => (
          <line key={x} x1={x} y1="10" x2={x} y2="158" stroke="var(--border)" strokeWidth="1" strokeDasharray="4 4" />
        ))}

        {/* ══ CLIENT box ══ */}
        <g>
          <rect x="12" y="68" width="76" height="44" rx="7"
            fill="var(--surface-1)" stroke="var(--border)" strokeWidth="1.5" />
          <text x="50" y="87" textAnchor="middle" fontSize="9.5" fontWeight="700" fill="var(--text-2)" fontFamily="var(--font-mono)">CLIENT</text>
          <text x="50" y="101" textAnchor="middle" fontSize="8" fill="var(--text-3)" fontFamily="var(--font-mono)">HTTP/REST</text>
        </g>

        {/* Client → Coordinator arrow */}
        <line x1="88" y1="90" x2="127" y2="90" stroke="var(--border)" strokeWidth="1.5" markerEnd="url(#arr)" />
        <AnimatePresence>
          {active && (
            <motion.circle key={active.id + '-c'} r={5} cy={90} fill={opColor}
              initial={{ cx: 88 }} animate={{ cx: 128 }}
              transition={{ duration: 0.35, ease: 'easeOut' }}
              filter="url(#glow)"
            />
          )}
        </AnimatePresence>

        {/* ══ COORDINATOR box ══ */}
        <g>
          <rect x="130" y="64" width="110" height="52" rx="7"
            fill="var(--surface-1)" strokeWidth="1.5"
            stroke={active ? opColor : 'var(--accent)'} />
          <AnimatePresence>
            {active && (
              <motion.rect key={active.id + '-coord-glow'} x="130" y="64" width="110" height="52" rx="7"
                fill={`${opColor}12`} stroke="none"
                initial={{ opacity: 0 }} animate={{ opacity: [0, 1, 1, 0] }}
                transition={{ duration: 1, times: [0, 0.1, 0.7, 1] }} />
            )}
          </AnimatePresence>
          <text x="185" y="85" textAnchor="middle" fontSize="9.5" fontWeight="700" fill="var(--accent)" fontFamily="var(--font-mono)">COORDINATOR</text>
          <text x="185" y="100" textAnchor="middle" fontSize="7.5" fill="var(--text-3)" fontFamily="var(--font-mono)">consistent hashing</text>
        </g>

        {/* Coordinator → 3 nodes (fan-out) */}
        {(['node-1', 'node-2', 'node-3'] as const).map((nid, i) => {
          const cy = [46, 90, 134][i];
          const isTarget = active?.nodeId === nid;
          const color = isTarget ? opColor : 'var(--border)';
          return (
            <g key={nid}>
              <line x1="240" y1="90" x2="258" y2={cy} stroke={color} strokeWidth={isTarget ? 1.5 : 1}
                strokeDasharray={isTarget ? "none" : "3 3"} markerEnd={`url(#arr${isTarget ? '-active' : ''})`} />
              <AnimatePresence>
                {active?.nodeId === nid && (
                  <motion.circle key={active.id + '-fan-' + nid} r={5} fill={opColor}
                    initial={{ cx: 240, cy: 90 }} animate={{ cx: 258, cy }}
                    transition={{ duration: 0.35, ease: 'easeOut', delay: 0.3 }}
                    filter="url(#glow)" />
                )}
              </AnimatePresence>
            </g>
          );
        })}

        {/* ══ 3 NODE boxes ══ */}
        {(['node-1', 'node-2', 'node-3'] as const).map((nid, i) => {
          const cy = [32, 76, 120][i];
          const color = nodeColors[nid] ?? NODE_COLORS[nid];
          const isTarget = active?.nodeId === nid;
          const isFlushing = flushNode === nid;
          const nodeState = nodes.find(n => n.id === nid);
          const fill = nodeState?.memtableFillPercent ?? 0;

          return (
            <g key={nid}>
              <rect x="262" y={cy} width="110" height="36" rx="6"
                fill="var(--surface-1)"
                stroke={isTarget ? opColor : color}
                strokeWidth={isTarget ? 2 : 1} />
              <AnimatePresence>
                {isTarget && (
                  <motion.rect key={nid + '-glow'} x="262" y={cy} width="110" height="36" rx="6"
                    fill={`${opColor}15`} stroke="none"
                    initial={{ opacity: 0 }} animate={{ opacity: [0, 1, 0] }}
                    transition={{ duration: 1 }} />
                )}
                {isFlushing && (
                  <motion.rect key={nid + '-flush'} x="262" y={cy} width="110" height="36" rx="6"
                    fill="var(--accent-subtle)" stroke="var(--accent)" strokeWidth="1.5"
                    initial={{ opacity: 0 }} animate={{ opacity: [0, 1, 0] }}
                    transition={{ duration: 1.5 }} />
                )}
              </AnimatePresence>

              <text x="317" y={cy + 13} textAnchor="middle" fontSize="9" fontWeight="700" fill={color} fontFamily="var(--font-mono)">
                {nid}
              </text>

              {/* Memtable fill bar */}
              <rect x="269" y={cy + 18} width="96" height="5" rx="2.5" fill="var(--surface-2)" />
              <motion.rect x="269" y={cy + 18} width={0} height="5" rx="2.5"
                animate={{ width: Math.max(3, 0.96 * fill) }}
                transition={{ duration: 0.5 }}
                fill={fill > 80 ? 'var(--amber)' : color} />
              <text x="317" y={cy + 24} textAnchor="middle" fontSize="7" fill="var(--text-3)" fontFamily="var(--font-mono)">
                mem {fill}%
              </text>
            </g>
          );
        })}

        {/* Node → WAL arrow */}
        <line x1="372" y1="90" x2="412" y2="90" stroke="var(--border)" strokeWidth="1.5" markerEnd="url(#arr)" />
        <AnimatePresence>
          {active && (
            <motion.circle key={active.id + '-towal'} r={5} cy={90} fill={opColor}
              initial={{ cx: 372 }} animate={{ cx: 412 }}
              transition={{ duration: 0.3, ease: 'easeOut', delay: 0.6 }}
              filter="url(#glow)" />
          )}
        </AnimatePresence>

        {/* ══ WAL box ══ */}
        <g>
          <rect x="415" y="66" width="80" height="48" rx="7"
            fill="var(--surface-1)" stroke="var(--amber)" strokeWidth="1.5" />
          <AnimatePresence>
            {active?.op === 'PUT' && (
              <motion.rect key={'wal-' + active.id} x="415" y="66" width="80" height="48" rx="7"
                fill="var(--amber-bg)" stroke="none"
                initial={{ opacity: 0 }} animate={{ opacity: [0, 1, 0] }}
                transition={{ duration: 0.8, delay: 0.65 }} />
            )}
          </AnimatePresence>
          <text x="455" y="87" textAnchor="middle" fontSize="9.5" fontWeight="700" fill="var(--amber)" fontFamily="var(--font-mono)">WAL</text>
          <text x="455" y="101" textAnchor="middle" fontSize="7.5" fill="var(--text-3)" fontFamily="var(--font-mono)">append + fsync</text>
        </g>

        {/* WAL → Memtable arrow */}
        <line x1="495" y1="90" x2="535" y2="90" stroke="var(--border)" strokeWidth="1.5" markerEnd="url(#arr)" />

        {/* ══ Memtable / SkipList box ══ */}
        <g>
          <rect x="538" y="62" width="100" height="56" rx="7"
            fill="var(--surface-1)" stroke="var(--blue)" strokeWidth="1.5" />
          <text x="588" y="84" textAnchor="middle" fontSize="9.5" fontWeight="700" fill="var(--blue)" fontFamily="var(--font-mono)">MEMTABLE</text>
          <text x="588" y="97" textAnchor="middle" fontSize="7.5" fill="var(--text-3)" fontFamily="var(--font-mono)">SkipList in-mem</text>
          {/* LRU cache tag */}
          <rect x="553" y="102" width="70" height="12" rx="4" fill="var(--blue-bg)" stroke="rgba(96,165,250,0.3)" strokeWidth="1" />
          <text x="588" y="112" textAnchor="middle" fontSize="7" fill="var(--blue)" fontFamily="var(--font-mono)">+ LRU cache</text>
        </g>

        {/* Flush: Memtable → SSTable (downward curved arrow) */}
        <AnimatePresence>
          {flushNode && (
            <motion.g initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
              <path d="M 588 118 Q 588 148 660 148" stroke="var(--accent)" strokeWidth="2" fill="none"
                strokeDasharray="4 2" markerEnd="url(#arr-active)" />
              <text x="620" y="143" fontSize="8" fill="var(--accent)" fontFamily="var(--font-mono)" fontWeight="600">flush</text>
            </motion.g>
          )}
        </AnimatePresence>

        {/* Memtable → Bloom filter arrow */}
        <line x1="638" y1="90" x2="654" y2="90" stroke="var(--border)" strokeWidth="1.5" markerEnd="url(#arr)" />

        {/* Bloom Filter */}
        <g>
          <rect x="658" y="72" width="90" height="36" rx="6"
            fill="var(--surface-1)" stroke="var(--purple)" strokeWidth="1.5" />
          <text x="703" y="88" textAnchor="middle" fontSize="9" fontWeight="700" fill="var(--purple)" fontFamily="var(--font-mono)">BLOOM</text>
          <text x="703" y="100" textAnchor="middle" fontSize="7.5" fill="var(--text-3)" fontFamily="var(--font-mono)">MurmurHash</text>
        </g>

        {/* Arrow Bloom → SSTable */}
        <line x1="748" y1="90" x2="776" y2="90" stroke="var(--border)" strokeWidth="1" markerEnd="url(#arr)" />

        {/* ══ SSTable box ══ */}
        <g>
          <rect x="780" y="62" width="100" height="56" rx="7"
            fill="var(--surface-1)"
            stroke={flushNode || compactNode ? 'var(--accent)' : 'var(--border)'}
            strokeWidth={flushNode || compactNode ? 2 : 1.5} />
          <AnimatePresence>
            {(flushNode || compactNode) && (
              <motion.rect key={'sst-active'} x="780" y="62" width="100" height="56" rx="7"
                fill="rgba(72,196,168,0.1)" stroke="none"
                initial={{ opacity: 0 }} animate={{ opacity: [0, 1, 0] }}
                transition={{ duration: 1.5 }} />
            )}
          </AnimatePresence>
          <text x="830" y="84" textAnchor="middle" fontSize="9.5" fontWeight="700" fill="var(--accent)" fontFamily="var(--font-mono)">SSTable</text>
          <text x="830" y="97" textAnchor="middle" fontSize="7.5" fill="var(--text-3)" fontFamily="var(--font-mono)">immutable disk</text>
          <text x="830" y="110" textAnchor="middle" fontSize="7.5" fill="var(--text-3)" fontFamily="var(--font-mono)">compaction</text>
        </g>
      </svg>
    </div>
  );
};
