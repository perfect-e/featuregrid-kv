import { useState, useEffect } from 'react';
import { useClusterStream } from '../hooks/useClusterStream';
import { NodeCard } from './NodeCard';
import { TerminalLog } from './TerminalLog';
import { WritePath } from './WritePath';
import { StatsBar } from './StatsBar';
import { BurstTest } from './BurstTest';
import { InteractiveStore } from './InteractiveStore';
import { StorageVisualizer } from './StorageVisualizer';
import { Moon, Sun, Database, Activity, GitBranch } from 'lucide-react';
import { HashRing } from './HashRing';

export const Dashboard = () => {
  const { nodes, events, connected, activeClients, killNode, restartNode, triggerCompaction } = useClusterStream();
  const [isDark, setIsDark] = useState(true);

  useEffect(() => {
    if (isDark) document.documentElement.classList.add('dark');
    else document.documentElement.classList.remove('dark');
  }, [isDark]);

  const upCount = nodes.filter(n => n.status === 'UP').length;

  return (
    <div className="min-h-screen" style={{ background: 'var(--bg)', color: 'var(--text-1)' }}>

      {/* ── Navbar ── */}
      <nav className="glass sticky top-0 z-50 px-5 py-3 flex items-center justify-between"
           style={{ borderBottom: '1px solid var(--glass-border)', borderRadius: 0 }}>
        <div className="flex items-center gap-3">
          {/* Logo mark */}
          <div style={{
            width: 32, height: 32, borderRadius: 8,
            background: 'linear-gradient(135deg, var(--accent) 0%, #60a5fa 100%)',
            display: 'flex', alignItems: 'center', justifyContent: 'center'
          }}>
            <Database className="text-white" size={16} />
          </div>

          <div>
            <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-1)', lineHeight: 1.2 }}>
              FeatureGrid KV
            </div>
            <div style={{ fontSize: 10, color: 'var(--text-3)', letterSpacing: '0.08em', textTransform: 'uppercase', fontWeight: 500 }}>
              Distributed Storage · LSM Engine
            </div>
          </div>

          {/* Separator */}
          <div style={{ width: 1, height: 24, background: 'var(--border)', margin: '0 8px' }} />

          {/* Cluster health badge */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--text-2)' }}>
            <div className={upCount === nodes.length && nodes.length > 0 ? 'dot-live' : 'dot-dead'} />
            <span style={{ fontWeight: 600 }}>{upCount}/{nodes.length} nodes</span>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {/* Live stream status */}
          <div style={{
            display: 'flex', alignItems: 'center', gap: 6,
            fontSize: 11, fontWeight: 600,
            color: connected ? 'var(--green)' : 'var(--red)',
            background: connected ? 'var(--green-bg)' : 'var(--red-bg)',
            border: `1px solid ${connected ? 'rgba(34,197,94,0.25)' : 'rgba(248,113,113,0.25)'}`,
            borderRadius: 99, padding: '4px 12px'
          }}>
            <Activity size={11} />
            {connected ? 'LIVE' : 'OFFLINE'}
          </div>

          {/* Active clients */}
          <div style={{
            display: 'flex', alignItems: 'center', gap: 5,
            fontSize: 11, color: 'var(--text-3)',
            background: 'var(--surface-1)', border: '1px solid var(--border)',
            borderRadius: 99, padding: '4px 12px'
          }}>
            <GitBranch size={11} />
            {activeClients} observer{activeClients !== 1 ? 's' : ''}
          </div>

          <button
            onClick={() => setIsDark(!isDark)}
            style={{
              width: 32, height: 32, borderRadius: 8, cursor: 'pointer',
              background: 'var(--surface-1)', border: '1px solid var(--border)',
              color: 'var(--text-2)', display: 'flex', alignItems: 'center', justifyContent: 'center'
            }}
          >
            {isDark ? <Sun size={14} /> : <Moon size={14} />}
          </button>
        </div>
      </nav>

      {/* ── Page content ── */}
      <div style={{ maxWidth: 1440, margin: '0 auto', padding: '28px 24px', display: 'flex', flexDirection: 'column', gap: 24 }}>

        {/* ── Stats strip ── */}
        <StatsBar events={events} nodes={nodes} connected={connected} />

        {/* ── Write path ── */}
        <WritePath events={events} nodes={nodes} />

        {/* ── Hash Ring ── */}
        <HashRing />

        {/* ── Main body: nodes left, terminal right ── */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 360px', gap: 20, alignItems: 'start' }}>

          {/* Left: node grid + burst */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

            {/* Section header */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <h2 style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-2)', letterSpacing: '0.05em', textTransform: 'uppercase' }}>
                Storage Nodes
              </h2>
              <span style={{
                fontSize: 11, fontWeight: 600, color: 'var(--text-3)',
                background: 'var(--surface-1)', border: '1px solid var(--border)',
                borderRadius: 6, padding: '3px 10px'
              }}>
                Consistent Hashing · 3 Shards
              </span>
            </div>

            {/* Node Cards */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 12 }}>
              {nodes.length === 0 && (
                <div className="card" style={{ gridColumn: '1/-1', padding: 48, textAlign: 'center', color: 'var(--text-3)' }}>
                  <Database size={28} style={{ margin: '0 auto 12px', opacity: 0.3 }} />
                  <p style={{ fontSize: 13 }}>Waiting for nodes to register…</p>
                </div>
              )}
              {[...nodes].sort((a, b) => a.id.localeCompare(b.id)).map(node => (
                <NodeCard
                  key={node.id}
                  node={node}
                  onKill={killNode}
                  onRestart={restartNode}
                  onCompact={triggerCompaction}
                />
              ))}
            </div>

            {/* Storage Visualizer */}
            <StorageVisualizer nodes={nodes} />

            {/* Interactive & Burst */}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <InteractiveStore />
              <BurstTest />
            </div>
          </div>

          {/* Right: activity stream */}
          <div style={{ position: 'sticky', top: 64 }}>
            <TerminalLog events={events} />
          </div>
        </div>
      </div>
    </div>
  );
};
