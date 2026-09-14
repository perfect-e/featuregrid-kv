import React, { useRef, useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { ClusterEvent } from '../hooks/useClusterStream';

interface TerminalLogProps {
  events: ClusterEvent[];
}

type FilterType = 'ALL' | 'OPERATION' | 'SST_FLUSH' | 'NODE_STATUS' | 'COMPACTION';

// ─── Formatting helpers ────────────────────────────────────────────────────

const OP_TOKENS: Record<string, { label: string; color: string }> = {
  PUT:    { label: 'PUT',    color: 'var(--green)' },
  GET:    { label: 'GET',    color: 'var(--blue)' },
  DELETE: { label: 'DEL',   color: 'var(--red)' },
};

function lineFor(ev: ClusterEvent): { prefix: string; prefixColor: string; msg: string; msgColor: string; latency?: number } {
  switch (ev.type) {
    case 'OPERATION': {
      const tok = OP_TOKENS[ev.op] ?? { label: ev.op, color: 'var(--text-3)' };
      const key = ev.key.length > 22 ? ev.key.slice(0, 22) + '…' : ev.key;
      const status = ev.success ? '' : ' ✗';
      return {
        prefix: tok.label, prefixColor: tok.color,
        msg: `${key}  →  ${ev.nodeId}${status}`,
        msgColor: ev.success ? 'var(--terminal-text)' : 'var(--red)',
        latency: ev.latencyMs,
      };
    }
    case 'SST_FLUSH':
      return {
        prefix: 'FLUSH', prefixColor: 'var(--accent)',
        msg: `${ev.nodeId}  ·  ${ev.extra?.sstableCount ?? '?'} sstables`,
        msgColor: 'var(--terminal-text)',
      };
    case 'COMPACTION':
      return {
        prefix: 'COMPACT', prefixColor: 'var(--purple)',
        msg: `${ev.nodeId}  →  ${ev.extra?.sstableCount ?? '?'} files`,
        msgColor: 'var(--terminal-text)',
      };
    case 'NODE_STATUS':
      return {
        prefix: 'NODE', prefixColor: 'var(--amber)',
        msg: `${ev.nodeId}  ${ev.extra?.status ?? '?'}`,
        msgColor: 'var(--terminal-text)',
      };
    default:
      return { prefix: ev.type, prefixColor: 'var(--text-3)', msg: ev.nodeId, msgColor: 'var(--text-3)' };
  }
}

function fmtTime(ts?: number): string {
  try {
    const d = new Date(ts || Date.now());
    if (isNaN(d.getTime())) return '  --:--:--';
    return d.toISOString().split('T')[1].slice(0, 8);
  } catch { return '  --:--:--'; }
}

// ─── Component ────────────────────────────────────────────────────────────

export const TerminalLog: React.FC<TerminalLogProps> = ({ events }) => {
  const [filter, setFilter] = useState<FilterType>('ALL');
  const [autoScroll, setAutoScroll] = useState(true);
  const scrollRef = useRef<HTMLDivElement>(null);

  const filtered = events.filter(e => {
    if (e.type === 'MEMTABLE') return false;
    return filter === 'ALL' || e.type === filter;
  });

  useEffect(() => {
    if (autoScroll && scrollRef.current) scrollRef.current.scrollTop = 0;
  }, [events, autoScroll]);

  const filters: { key: FilterType; label: string }[] = [
    { key: 'ALL', label: 'all' },
    { key: 'OPERATION', label: 'ops' },
    { key: 'SST_FLUSH', label: 'flush' },
    { key: 'COMPACTION', label: 'compact' },
    { key: 'NODE_STATUS', label: 'nodes' },
  ];

  return (
    <div style={{
      display: 'flex', flexDirection: 'column',
      background: 'var(--terminal-bg)',
      border: '1px solid var(--terminal-border)',
      borderRadius: 12,
      overflow: 'hidden',
      height: 560,
    }}>

      {/* ── Terminal titlebar ── */}
      <div style={{
        display: 'flex', alignItems: 'center',
        padding: '10px 14px',
        borderBottom: '1px solid var(--terminal-border)',
        background: 'var(--terminal-header)',
        gap: 10,
        flexShrink: 0,
      }}>
        {/* macOS-style dots */}
        <div style={{ display: 'flex', gap: 5 }}>
          <div style={{ width: 10, height: 10, borderRadius: '50%', background: '#f87171', opacity: 0.8 }} />
          <div style={{ width: 10, height: 10, borderRadius: '50%', background: '#f59e0b', opacity: 0.8 }} />
          <div style={{ width: 10, height: 10, borderRadius: '50%', background: '#22c55e', opacity: 0.8 }} />
        </div>

        <div style={{ flex: 1, textAlign: 'center', fontSize: 10, fontWeight: 600, color: 'var(--text-3)', fontFamily: 'var(--font-mono)', letterSpacing: '0.05em' }}>
          kv-cluster — activity stream
        </div>

        {/* Event count badge */}
        <div style={{
          fontSize: 9, fontWeight: 700, fontFamily: 'var(--font-mono)',
          color: 'var(--terminal-btn-text)',
          background: 'var(--terminal-btn-bg)',
          border: '1px solid var(--terminal-border)',
          borderRadius: 4, padding: '2px 7px',
        }}>
          {filtered.length}
        </div>
      </div>

      {/* ── Filter row ── */}
      <div style={{
        display: 'flex', gap: 4, padding: '8px 14px',
        borderBottom: '1px solid var(--terminal-border)',
        background: 'var(--terminal-header)',
        flexShrink: 0,
      }}>
        <span style={{ fontSize: 9, color: 'var(--text-4)', fontFamily: 'var(--font-mono)', alignSelf: 'center', marginRight: 4 }}>
          filter:
        </span>
        {filters.map(f => (
          <button key={f.key} onClick={() => setFilter(f.key)} style={{
            padding: '2px 10px', borderRadius: 4, cursor: 'pointer', fontFamily: 'var(--font-mono)',
            fontSize: 10, fontWeight: 600,
            background: filter === f.key ? 'var(--accent-subtle)' : 'transparent',
            color: filter === f.key ? 'var(--accent)' : 'var(--terminal-btn-text)',
            border: filter === f.key ? '1px solid var(--accent-muted)' : '1px solid transparent',
            transition: 'all 0.12s',
          }}>
            {f.label}
          </button>
        ))}
      </div>

      {/* ── Log lines ── */}
      <div
        ref={scrollRef}
        onMouseEnter={() => setAutoScroll(false)}
        onMouseLeave={() => setAutoScroll(true)}
        style={{
          flex: 1, overflowY: 'auto',
          padding: '8px 0',
          fontFamily: 'var(--font-mono)',
          fontSize: 11,
          lineHeight: 1.7,
        }}
      >
        <AnimatePresence initial={false}>
          {filtered.map(ev => {
            const { prefix, prefixColor, msg, msgColor, latency } = lineFor(ev);
            const maxBar = 200;
            const barW = latency ? Math.min(100, (latency / maxBar) * 100) : 0;
            const barColor = latency && latency > 100 ? 'var(--amber)' : latency && latency > 30 ? 'var(--blue)' : 'var(--green)';

            return (
              <motion.div
                key={ev.id}
                initial={{ opacity: 0, x: -4 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.12 }}
                style={{
                  display: 'flex', alignItems: 'center', gap: 0,
                  padding: '0 14px',
                  transition: 'background 0.1s',
                }}
                onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-1)')}
                onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
              >
                {/* Timestamp */}
                <span style={{ color: 'var(--terminal-timestamp)', width: 62, flexShrink: 0 }}>
                  {fmtTime(ev.timestampMs)}
                </span>

                {/* Op label */}
                <span style={{
                  color: prefixColor, width: 58, flexShrink: 0,
                  fontWeight: 700, fontSize: 10, letterSpacing: '0.04em',
                }}>
                  {prefix}
                </span>

                {/* Message */}
                <span style={{ color: msgColor, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {msg}
                </span>

                {/* Latency mini-bar */}
                {latency != null && latency > 0 && (
                  <div style={{ display: 'flex', alignItems: 'center', gap: 5, flexShrink: 0, marginLeft: 8 }}>
                    <div style={{ width: 36, height: 3, background: 'var(--terminal-border)', borderRadius: 99, overflow: 'hidden' }}>
                      <div style={{ width: `${barW}%`, height: '100%', background: barColor, borderRadius: 99 }} />
                    </div>
                    <span style={{ color: 'var(--terminal-timestamp)', fontSize: 9, width: 30, textAlign: 'right' }}>
                      {latency.toFixed(0)}ms
                    </span>
                  </div>
                )}
              </motion.div>
            );
          })}
        </AnimatePresence>

        {filtered.length === 0 && (
          <div style={{ padding: '40px 20px', textAlign: 'center' }}>
            <div style={{ fontSize: 11, color: 'var(--terminal-timestamp)', fontFamily: 'var(--font-mono)' }}>
              $ waiting for events...
            </div>
            <div style={{ marginTop: 6 }}>
              <span style={{ fontSize: 11, color: 'var(--accent)', fontFamily: 'var(--font-mono)' }}>▋</span>
            </div>
          </div>
        )}
      </div>

      {/* ── Footer ── */}
      <div style={{
        display: 'flex', alignItems: 'center',
        justifyContent: 'space-between',
        padding: '6px 14px',
        borderTop: '1px solid var(--terminal-border)',
        background: 'var(--terminal-header)',
        flexShrink: 0,
      }}>
        <span style={{ fontSize: 9, color: 'var(--terminal-timestamp)', fontFamily: 'var(--font-mono)' }}>
          {autoScroll ? '↑ auto-scroll' : '⏸ paused'}
        </span>
        <span style={{ fontSize: 9, color: 'var(--terminal-timestamp)', fontFamily: 'var(--font-mono)' }}>
          {events.length} events
        </span>
      </div>
    </div>
  );
};
