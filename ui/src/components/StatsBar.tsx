import React, { useMemo } from 'react';
import type { ClusterEvent, NodeState } from '../hooks/useClusterStream';
import { motion } from 'framer-motion';

interface StatsBarProps {
  events: ClusterEvent[];
  nodes: NodeState[];
  connected: boolean;
}

export const StatsBar: React.FC<StatsBarProps> = ({ events, nodes }) => {
  const stats = useMemo(() => {
    const ops  = events.filter(e => e.type === 'OPERATION');
    const puts = ops.filter(e => e.op === 'PUT').length;
    const gets = ops.filter(e => e.op === 'GET').length;
    const dels = ops.filter(e => e.op === 'DELETE').length;
    const failed = ops.filter(e => !e.success).length;

    const now    = Date.now();
    const recent = ops.filter(e => (now - (e.timestampMs || now)) < 10_000);
    const opsPerSec = (recent.length / 10).toFixed(1);

    const latencies = ops.filter(e => (e.latencyMs || 0) > 0).map(e => e.latencyMs!);
    const avgMs     = latencies.length > 0
      ? (latencies.reduce((s, v) => s + v, 0) / latencies.length).toFixed(1)
      : null;
    const p99Ms     = latencies.length > 0
      ? latencies.sort((a, b) => a - b)[Math.floor(latencies.length * 0.99)] ?? latencies[latencies.length - 1]
      : null;

    const totalSst  = nodes.reduce((s, n) => s + (n.sstableCount || 0), 0);
    const avgCache  = nodes.filter(n => n.status === 'UP').length > 0
      ? Math.round(nodes.filter(n => n.status === 'UP').reduce((s, n) => s + n.cacheHitPercent, 0) / nodes.filter(n => n.status === 'UP').length)
      : 0;

    return { puts, gets, dels, failed, totalOps: ops.length, opsPerSec, avgMs, p99Ms, totalSst, avgCache };
  }, [events, nodes]);

  return (
    <div style={{
      display: 'grid',
      gridTemplateColumns: '1fr 1px 1fr 1px 1fr 1px 1fr 1px 1fr',
      background: 'var(--surface-0)',
      border: '1px solid var(--border)',
      borderRadius: 12,
      overflow: 'hidden'
    }}>
      <Stat
        label="Ops / sec"
        value={stats.opsPerSec}
        sub="last 10s"
        accent="var(--accent)"
        hero
      />
      <Divider />
      <Stat
        label="Avg Latency"
        value={stats.avgMs ? `${stats.avgMs}ms` : '—'}
        sub={stats.p99Ms ? `p99 ${stats.p99Ms}ms` : 'no data'}
      />
      <Divider />
      <Stat
        label="PUT / GET / DEL"
        value={`${stats.puts} · ${stats.gets} · ${stats.dels}`}
        sub={`${stats.totalOps} total ops`}
        mono
      />
      <Divider />
      <Stat
        label="LRU Cache hit"
        value={`${stats.avgCache}%`}
        sub="cluster avg"
        valueColor={stats.avgCache > 60 ? 'var(--green)' : stats.avgCache > 20 ? 'var(--amber)' : 'var(--text-3)'}
      />
      <Divider />
      <Stat
        label="SSTables"
        value={stats.totalSst}
        sub="all nodes"
        valueColor={stats.totalSst >= 12 ? 'var(--amber)' : 'var(--text-1)'}
      />
    </div>
  );
};

/* ─── Sub-components ──────────────────────────────────────────────────────── */

const Divider = () => (
  <div style={{ background: 'var(--border)', width: 1, alignSelf: 'stretch' }} />
);

interface StatProps {
  label: string;
  value: string | number;
  sub: string;
  accent?: string;
  mono?: boolean;
  valueColor?: string;
  hero?: boolean;
}

const Stat: React.FC<StatProps> = ({ label, value, sub, accent, mono, valueColor, hero }) => (
  <motion.div
    initial={{ opacity: 0, y: 4 }}
    animate={{ opacity: 1, y: 0 }}
    style={{
      padding: '14px 20px',
      display: 'flex',
      flexDirection: 'column',
      gap: 2
    }}
  >
    <div style={{
      fontSize: 10,
      fontWeight: 600,
      color: 'var(--text-3)',
      letterSpacing: '0.07em',
      textTransform: 'uppercase',
      marginBottom: 4
    }}>
      {label}
    </div>

    <div style={{
      fontSize: hero ? 26 : 18,
      fontWeight: hero ? 800 : 700,
      fontFamily: mono ? 'var(--font-mono)' : 'inherit',
      color: valueColor || (accent ? accent : 'var(--text-1)'),
      lineHeight: 1,
      letterSpacing: hero ? '-0.03em' : '-0.01em',
    }}>
      {value}
    </div>

    <div style={{ fontSize: 11, color: 'var(--text-3)', marginTop: 2 }}>
      {sub}
    </div>
  </motion.div>
);
