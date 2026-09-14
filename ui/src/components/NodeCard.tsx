import React, { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { NodeState } from '../hooks/useClusterStream';
import { Server, Layers, HardDrive, Cpu, RefreshCw, Loader2 } from 'lucide-react';
import { PieChart, Pie, Cell } from 'recharts';

interface NodeCardProps {
  node: NodeState;
  onKill: (id: string) => void;
  onRestart: (id: string) => void;
  onCompact: (id: string, httpPort: number) => void;
}

export const NodeCard: React.FC<NodeCardProps> = ({ node, onKill, onRestart, onCompact }) => {
  const isUp     = node.status === 'UP';
  const isKilled = node.status === 'KILLED' || node.blacklisted;
  const [compacting, setCompacting] = useState(false);

  const handleCompact = async () => {
    setCompacting(true);
    await onCompact(node.id, node.httpPort);
    setTimeout(() => setCompacting(false), 3000);
  };

  const formatSize = (bytes: number) => {
    if (!bytes || bytes < 0) return '—';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1048576).toFixed(2) + ' MB';
  };

  // Semantic status
  const statusColor = isUp ? 'var(--green)' : isKilled ? 'var(--red)' : 'var(--amber)';
  const statusBg    = isUp ? 'var(--green-bg)' : isKilled ? 'var(--red-bg)' : 'var(--amber-bg)';
  const statusLabel = isKilled ? 'Isolated' : isUp ? 'Healthy' : 'Down';

  // Memtable
  const fillPct = node.memtableFillPercent;
  const fillColor = fillPct > 80 ? 'var(--amber)' : 'var(--accent)';
  const memPie = [
    { name: 'used',  value: fillPct },
    { name: 'free',  value: Math.max(0, 100 - fillPct) },
  ];

  // SSTable slots (max 8 visual)
  const MAX_SLOTS = 8;
  const sstColor = node.sstableCount >= 4 ? 'var(--amber)' : 'var(--accent)';

  // Cache quality
  const cacheColor = node.cacheHitPercent > 60 ? 'var(--green)'
                   : node.cacheHitPercent > 20 ? 'var(--amber)'
                   : 'var(--text-3)';

  // Raft role styling
  const isLeader    = node.role === 'LEADER';
  const isCandidate = node.role === 'CANDIDATE';
  const roleColor   = isLeader ? '#f59e0b' : isCandidate ? '#a855f7' : 'var(--text-4)';
  const roleBg      = isLeader ? '#fef3c7' : isCandidate ? 'rgba(168,85,247,0.12)' : 'transparent';

  return (
    <motion.div
      layout
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: isKilled ? 0.65 : 1, y: 0 }}
      transition={{ duration: 0.2 }}
      style={{
        background: 'var(--surface-0)',
        border: `1px solid ${isLeader ? '#f59e0b' : isUp ? 'var(--border)' : statusColor}`,
        borderRadius: 12,
        overflow: 'hidden',
        filter: isKilled ? 'grayscale(0.3)' : 'none',
        display: 'flex',
        flexDirection: 'column',
        boxShadow: isLeader ? '0 0 0 1px rgba(245,158,11,0.3), 0 4px 12px rgba(245,158,11,0.08)' : 'none',
      }}
    >
      {/* ── Header ── */}
      <div style={{
        padding: '12px 16px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        borderBottom: '1px solid var(--border-subtle)',
        background: isUp ? 'transparent' : statusBg,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          {/* Status indicator */}
          <div style={{ position: 'relative', flexShrink: 0 }}>
            <div style={{
              width: 34, height: 34, borderRadius: 8,
              background: isLeader ? 'rgba(245,158,11,0.12)' : 'var(--surface-1)',
              border: `1px solid ${isLeader ? 'rgba(245,158,11,0.4)' : 'var(--border)'}`,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              color: isLeader ? '#f59e0b' : statusColor,
            }}>
              <Server size={16} />
            </div>
            {isLeader && (
              <span style={{
                position: 'absolute', top: -6, right: -6,
                fontSize: 12,
              }}>👑</span>
            )}
            {!isLeader && isUp && (
              <span style={{
                position: 'absolute', top: -2, right: -2,
                width: 8, height: 8, borderRadius: '50%',
                background: 'var(--green)',
                boxShadow: '0 0 0 2px var(--surface-0)',
              }} />
            )}
          </div>

          <div>
            <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-1)', letterSpacing: '-0.01em' }}>
              {node.id}
            </div>
            <div style={{ fontSize: 10, color: 'var(--text-3)', fontFamily: 'var(--font-mono)', marginTop: 1 }}>
              :{node.grpcPort}
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 3 }}>
          <span style={{
            fontSize: 10, fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase',
            color: statusColor,
            background: statusBg,
            padding: '2px 8px', borderRadius: 4,
          }}>
            {statusLabel}
          </span>
          {node.role && (
            <span style={{
              fontSize: 9, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase',
              color: roleColor,
              background: roleBg,
              padding: '1px 6px', borderRadius: 3,
              border: isLeader ? '1px solid rgba(245,158,11,0.3)' : 'none',
            }}>
              {node.role}
            </span>
          )}
        </div>
      </div>

      {/* ── Metrics ── */}
      <div style={{ padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 12, flex: 1 }}>

        {/* Memtable — visually dominant row */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {/* Donut */}
          <div style={{ position: 'relative', flexShrink: 0, width: 44, height: 44 }}>
            <PieChart width={44} height={44}>
              <Pie data={memPie} cx="50%" cy="50%" innerRadius={14} outerRadius={22}
                   startAngle={90} endAngle={-270} dataKey="value" stroke="none">
                <Cell fill={fillColor} />
                <Cell fill="var(--surface-2)" />
              </Pie>
            </PieChart>
            <div style={{
              position: 'absolute', inset: 0, display: 'flex',
              alignItems: 'center', justifyContent: 'center',
              fontSize: 8, fontWeight: 700, color: 'var(--text-2)', fontFamily: 'var(--font-mono)'
            }}>
              {fillPct}%
            </div>
          </div>

          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 5 }}>
              <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-2)' }}>Memtable</span>
              <span style={{ fontSize: 10, color: 'var(--text-3)', fontFamily: 'var(--font-mono)' }}>SkipList</span>
            </div>
            {/* Progress bar */}
            <div style={{ height: 5, background: 'var(--surface-2)', borderRadius: 99, overflow: 'hidden' }}>
              <motion.div
                animate={{ width: `${fillPct}%` }}
                transition={{ duration: 0.5, ease: 'easeOut' }}
                style={{
                  height: '100%', borderRadius: 99,
                  background: fillPct > 80
                    ? 'linear-gradient(to right, var(--amber), var(--red))'
                    : 'linear-gradient(to right, var(--accent), #60a5fa)',
                }}
              />
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 4 }}>
              <span style={{ fontSize: 9, color: 'var(--text-3)' }}>WAL {formatSize(node.walSizeBytes)}</span>
              <span style={{ fontSize: 9, color: fillPct > 80 ? 'var(--amber)' : 'var(--text-3)' }}>
                {fillPct > 80 ? '⚠ flush imminent' : `${fillPct}% full`}
              </span>
            </div>
          </div>
        </div>

        {/* Divider */}
        <div style={{ height: 1, background: 'var(--border-subtle)' }} />

        {/* SSTable + Cache — side by side */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>

          {/* SSTables */}
          <div style={{ background: 'var(--surface-1)', border: '1px solid var(--border-subtle)', borderRadius: 8, padding: '10px 12px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 5, marginBottom: 8 }}>
              <Layers size={11} style={{ color: 'var(--text-3)' }} />
              <span style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-3)', letterSpacing: '0.05em', textTransform: 'uppercase' }}>SSTables</span>
            </div>

            {/* Stacked file slots */}
            <div style={{ display: 'flex', gap: 3, alignItems: 'flex-end', height: 20, marginBottom: 6 }}>
              {Array.from({ length: MAX_SLOTS }, (_, i) => {
                const filled = i < node.sstableCount;
                return (
                  <motion.div
                    key={i}
                    initial={false}
                    animate={{ scaleY: filled ? 1 : 0.25, opacity: filled ? 1 : 0.18 }}
                    transition={{ duration: 0.25, delay: i * 0.03 }}
                    style={{
                      flex: 1, height: '100%', borderRadius: 3,
                      transformOrigin: 'bottom',
                      background: filled ? sstColor : 'var(--surface-2)',
                    }}
                  />
                );
              })}
            </div>

            <div style={{ display: 'flex', alignItems: 'baseline', gap: 3 }}>
              <span style={{ fontSize: 16, fontWeight: 800, color: sstColor, fontFamily: 'var(--font-mono)', letterSpacing: '-0.03em', lineHeight: 1 }}>
                {node.sstableCount}
              </span>
              <span style={{ fontSize: 10, color: 'var(--text-3)' }}>files</span>
            </div>

            {node.sstableCount >= 4 && (
              <div style={{ marginTop: 5, fontSize: 9, color: 'var(--amber)', fontWeight: 600 }}>
                ⚠ compact recommended
              </div>
            )}
          </div>

          {/* LRU Cache */}
          <div style={{ background: 'var(--surface-1)', border: '1px solid var(--border-subtle)', borderRadius: 8, padding: '10px 12px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 5, marginBottom: 8 }}>
              <Cpu size={11} style={{ color: 'var(--text-3)' }} />
              <span style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-3)', letterSpacing: '0.05em', textTransform: 'uppercase' }}>LRU Cache</span>
            </div>

            {/* Arc fill bar */}
            <div style={{ height: 5, background: 'var(--surface-2)', borderRadius: 99, overflow: 'hidden', marginBottom: 6 }}>
              <motion.div
                animate={{ width: `${node.cacheHitPercent}%` }}
                transition={{ duration: 0.5 }}
                style={{
                  height: '100%', borderRadius: 99,
                  background: node.cacheHitPercent > 60
                    ? 'linear-gradient(to right, var(--green), #4ade80)'
                    : node.cacheHitPercent > 20
                    ? 'linear-gradient(to right, var(--amber), #fcd34d)'
                    : 'var(--surface-2)',
                }}
              />
            </div>

            <div style={{ display: 'flex', alignItems: 'baseline', gap: 3 }}>
              <span style={{ fontSize: 16, fontWeight: 800, color: cacheColor, fontFamily: 'var(--font-mono)', letterSpacing: '-0.03em', lineHeight: 1 }}>
                {node.cacheHitPercent}
              </span>
              <span style={{ fontSize: 10, color: 'var(--text-3)' }}>% hit</span>
            </div>

            <div style={{ marginTop: 5, fontSize: 9, color: 'var(--text-3)' }}>
              {node.cacheHitPercent > 60 ? '✓ warm cache' : node.cacheHitPercent > 0 ? 'warming up…' : 'cold start'}
            </div>
          </div>
        </div>

        {/* WAL inline row */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '6px 10px', background: 'var(--surface-1)', borderRadius: 7 }}>
          <HardDrive size={11} style={{ color: 'var(--text-3)' }} />
          <span style={{ fontSize: 10, color: 'var(--text-3)', flex: 1 }}>WAL log</span>
          <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-2)', fontFamily: 'var(--font-mono)' }}>
            {formatSize(node.walSizeBytes)}
          </span>
        </div>
      </div>

      {/* ── Controls ── */}
      <div style={{
        padding: '10px 14px',
        borderTop: '1px solid var(--border-subtle)',
        display: 'flex', gap: 8
      }}>
        {!isKilled ? (
          <button
            onClick={() => onKill(node.id)}
            style={{
              flex: 1, padding: '7px 0', borderRadius: 7, cursor: 'pointer',
              fontSize: 11, fontWeight: 700, letterSpacing: '0.03em',
              background: 'var(--red-bg)',
              color: 'var(--red)',
              border: '1px solid rgba(220,38,38,0.2)',
              transition: 'opacity 0.15s',
            }}
            onMouseEnter={e => (e.currentTarget.style.opacity = '0.8')}
            onMouseLeave={e => (e.currentTarget.style.opacity = '1')}
          >
            Isolate
          </button>
        ) : (
          <button
            onClick={() => onRestart(node.id)}
            style={{
              flex: 1, padding: '7px 0', borderRadius: 7, cursor: 'pointer',
              fontSize: 11, fontWeight: 700, letterSpacing: '0.03em',
              background: 'var(--green-bg)',
              color: 'var(--green)',
              border: '1px solid rgba(22,163,74,0.2)',
              transition: 'opacity 0.15s',
            }}
            onMouseEnter={e => (e.currentTarget.style.opacity = '0.8')}
            onMouseLeave={e => (e.currentTarget.style.opacity = '1')}
          >
            Reconnect
          </button>
        )}

        <AnimatePresence>
          {isUp && (
            <motion.button
              initial={{ opacity: 0, width: 0 }}
              animate={{ opacity: 1, width: 'auto' }}
              exit={{ opacity: 0, width: 0 }}
              onClick={handleCompact}
              disabled={compacting}
              style={{
                padding: '7px 14px', borderRadius: 7, cursor: 'pointer',
                fontSize: 11, fontWeight: 700, letterSpacing: '0.03em',
                background: 'transparent',
                color: 'var(--accent)',
                border: '1px solid var(--accent)',
                display: 'flex', alignItems: 'center', gap: 5,
                whiteSpace: 'nowrap',
                opacity: compacting ? 0.6 : 1,
                transition: 'opacity 0.15s, background 0.15s',
              }}
              onMouseEnter={e => !compacting && (e.currentTarget.style.background = 'var(--accent-subtle)')}
              onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
            >
              {compacting ? <><Loader2 size={12} className="animate-spin" />Running</> : <><RefreshCw size={12} />Compact</>}
            </motion.button>
          )}
        </AnimatePresence>
      </div>
    </motion.div>
  );
};
