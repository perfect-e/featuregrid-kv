import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { NodeState } from '../hooks/useClusterStream';

interface StorageVisualizerProps {
  nodes: NodeState[];
}

export const StorageVisualizer: React.FC<StorageVisualizerProps> = ({ nodes }) => {
  const [selectedNodeId, setSelectedNodeId] = useState<string>('');
  const [dump, setDump] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!selectedNodeId && nodes.length > 0) {
      setSelectedNodeId(nodes[0].id);
    }
  }, [nodes, selectedNodeId]);

  useEffect(() => {
    if (!selectedNodeId) return;

    const node = nodes.find(n => n.id === selectedNodeId);
    if (!node || node.status !== 'UP') {
      setDump(null);
      setError('Node is offline or unselectable.');
      return;
    }

    const fetchDump = async () => {
      try {
        const res = await fetch(`http://localhost:${node.httpPort}/api/v1/storage/debug/dump`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        setDump(data);
        setError(null);
      } catch (err: any) {
        setError(err.message);
      }
    };

    fetchDump();
    const interval = setInterval(fetchDump, 2000);
    return () => clearInterval(interval);
  }, [selectedNodeId, nodes]);

  return (
    <div style={{
      background: 'var(--surface-0)', border: '1px solid var(--border)', borderRadius: 12,
      padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 16
    }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{
            width: 28, height: 28, borderRadius: 7,
            background: 'var(--surface-1)', border: '1px solid var(--border)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            color: 'var(--text-2)'
          }}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <rect x="2" y="2" width="20" height="8" rx="2" ry="2"></rect>
              <rect x="2" y="14" width="20" height="8" rx="2" ry="2"></rect>
              <line x1="6" y1="6" x2="6.01" y2="6"></line>
              <line x1="6" y1="18" x2="6.01" y2="18"></line>
            </svg>
          </div>
          <div>
            <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-1)' }}>Storage Visualizer</div>
            <div style={{ fontSize: 10, color: 'var(--text-3)' }}>inspect LSM internals</div>
          </div>
        </div>

        <select
          value={selectedNodeId}
          onChange={e => setSelectedNodeId(e.target.value)}
          style={{
            background: 'var(--surface-1)', border: '1px solid var(--border)', color: 'var(--text-1)',
            padding: '4px 8px', borderRadius: 6, fontSize: 11, fontWeight: 600, outline: 'none'
          }}
        >
          {nodes.map(n => (
            <option key={n.id} value={n.id}>{n.id} ({n.status})</option>
          ))}
        </select>
      </div>

      {error ? (
        <div style={{ padding: 12, color: 'var(--red)', fontSize: 11, background: 'var(--surface-1)', borderRadius: 6 }}>
          {error}
        </div>
      ) : dump ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          
          {/* Memtable */}
          <div style={{ border: '1px solid var(--amber)', borderRadius: 8, padding: 12, background: 'rgba(245, 158, 11, 0.05)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
              <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--amber)' }}>Memtable</span>
              <span style={{ fontSize: 10, color: 'var(--text-3)' }}>
                {dump.memtable.fillPercent}% full • {dump.memtable.liveCount} keys • {Math.round(dump.memtable.sizeBytes / 1024)} KB
              </span>
            </div>
            
            <div style={{
              background: 'var(--surface-0)', border: '1px solid var(--border)', borderRadius: 6,
              height: 100, overflowY: 'auto', padding: 8,
              display: 'flex', flexDirection: 'column', gap: 4
            }}>
              {dump.memtable.entries.length === 0 ? (
                <div style={{ color: 'var(--text-4)', fontSize: 10, fontStyle: 'italic' }}>Empty</div>
              ) : (
                dump.memtable.entries.map((e: any) => (
                  <div key={e.key} style={{ display: 'flex', gap: 8, fontSize: 10, fontFamily: 'var(--font-mono)' }}>
                    <span style={{ color: 'var(--blue)', width: 80, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{e.key}</span>
                    {e.tombstone ? (
                      <span style={{ color: 'var(--red)', fontStyle: 'italic' }}>&lt;tombstone&gt;</span>
                    ) : (
                      <span style={{ color: 'var(--text-2)' }}>{e.value}</span>
                    )}
                  </div>
                ))
              )}
            </div>
          </div>

          {/* SSTables */}
          <div>
            <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--text-1)', marginBottom: 8, display: 'flex', justifyContent: 'space-between' }}>
              <span>Disk SSTables ({dump.sstables.length})</span>
            </div>
            
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              <AnimatePresence>
                {dump.sstables.map((sst: any) => (
                  <motion.div
                    key={sst.filename}
                    layout
                    initial={{ opacity: 0, scale: 0.8, y: -20 }}
                    animate={{ opacity: 1, scale: 1, y: 0 }}
                    exit={{ opacity: 0, scale: 0.8, y: 20 }}
                    transition={{ type: "spring", stiffness: 300, damping: 25 }}
                    style={{
                      background: 'var(--surface-1)', border: '1px solid var(--border)', borderRadius: 6,
                      padding: 10, minWidth: 140, display: 'flex', flexDirection: 'column', gap: 4
                    }}
                  >
                    <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--text-1)' }}>{sst.filename}</div>
                    <div style={{ fontSize: 9, color: 'var(--text-3)' }}>Size: {sst.sizeHuman}</div>
                    <div style={{ fontSize: 9, color: 'var(--text-3)' }}>Keys: {sst.entryCount}</div>
                    <div style={{ fontSize: 9, color: 'var(--text-4)', marginTop: 4 }}>
                      [{sst.firstKey} ... {sst.lastKey}]
                    </div>
                  </motion.div>
                ))}
                {dump.sstables.length === 0 && (
                  <div style={{ fontSize: 10, color: 'var(--text-4)' }}>No SSTables on disk.</div>
                )}
              </AnimatePresence>
            </div>
          </div>

        </div>
      ) : (
        <div style={{ fontSize: 10, color: 'var(--text-3)' }}>Loading state...</div>
      )}
    </div>
  );
};
