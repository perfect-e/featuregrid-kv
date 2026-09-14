import React, { useState } from 'react';
import { motion } from 'framer-motion';

interface BurstTestProps {
  coordinatorUrl?: string;
}

export const BurstTest: React.FC<BurstTestProps> = ({
  coordinatorUrl = 'http://localhost:8080',
}) => {
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState(0);
  const [total, setTotal] = useState(50);
  const [delay, setDelay] = useState(80);
  const [done, setDone] = useState(false);
  const [results, setResults] = useState<{ ok: number; fail: number }>({ ok: 0, fail: 0 });

  const fire = async () => {
    setRunning(true);
    setDone(false);
    setProgress(0);
    setResults({ ok: 0, fail: 0 });

    let ok = 0, fail = 0;

    for (let i = 1; i <= total; i++) {
      const key   = `burst-${Date.now()}-${i}`;
      const value = `val-${i}`;
      try {
        const res = await fetch(`${coordinatorUrl}/api/v1/kv/${key}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ value, ttlMs: 0 }),
        });
        if (res.ok) ok++; else fail++;
      } catch {
        fail++;
      }
      setProgress(i);
      setResults({ ok, fail });
      if (delay > 0) await new Promise(r => setTimeout(r, delay));
    }

    setRunning(false);
    setDone(true);
  };

  const pct = total > 0 ? (progress / total) * 100 : 0;

  return (
    <div style={{
      background: 'var(--surface-0)', border: '1px solid var(--border)', borderRadius: 12,
      padding: '14px 16px',
    }}>
      {/* Header row */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{
            width: 28, height: 28, borderRadius: 7,
            background: running ? 'rgba(245,158,11,0.15)' : 'var(--surface-1)',
            border: `1px solid ${running ? 'rgba(245,158,11,0.4)' : 'var(--border)'}`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            color: running ? 'var(--amber)' : 'var(--text-3)',
            transition: 'all 0.2s',
          }}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
              {running
                ? <><path d="M21 12a9 9 0 1 1-6.219-8.56"/><path d="M12 6v6l4 2"/></>
                : <><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></>
              }
            </svg>
          </div>
          <div>
            <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-1)' }}>Burst Write Test</div>
            <div style={{ fontSize: 10, color: 'var(--text-3)' }}>stress memtable flushing</div>
          </div>
        </div>

        {/* Result badge */}
        {done && (
          <div style={{
            display: 'flex', gap: 8, fontSize: 10, fontFamily: 'var(--font-mono)',
            color: 'var(--text-2)',
          }}>
            <span style={{ color: 'var(--green)', fontWeight: 700 }}>✓ {results.ok}</span>
            {results.fail > 0 && <span style={{ color: 'var(--red)', fontWeight: 700 }}>✗ {results.fail}</span>}
          </div>
        )}
      </div>

      {/* Controls row */}
      <div style={{ display: 'flex', gap: 12, marginBottom: 12 }}>
        {[
          { label: 'Writes', val: total, set: setTotal, min: 5, max: 500, w: 70 },
          { label: 'Delay ms', val: delay, set: setDelay, min: 0, max: 500, w: 70 },
        ].map(({ label, val, set, min, max, w }) => (
          <label key={label} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span style={{ fontSize: 9, fontWeight: 600, color: 'var(--text-3)', letterSpacing: '0.06em', textTransform: 'uppercase' }}>
              {label}
            </span>
            <input
              type="number"
              value={val}
              min={min}
              max={max}
              disabled={running}
              onChange={e => set(Number(e.target.value))}
              style={{
                width: w, padding: '5px 8px',
                borderRadius: 6,
                background: 'var(--surface-1)',
                border: '1px solid var(--border)',
                color: 'var(--text-1)',
                fontSize: 12, fontFamily: 'var(--font-mono)', fontWeight: 600,
                outline: 'none',
                opacity: running ? 0.6 : 1,
              }}
            />
          </label>
        ))}

        {/* Progress counter */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', paddingBottom: 2 }}>
          <span style={{ fontSize: 10, color: 'var(--text-3)', fontFamily: 'var(--font-mono)' }}>
            {running ? `${progress} / ${total}` : `${total} writes`}
          </span>
        </div>
      </div>

      {/* Progress bar */}
      <div style={{ height: 4, background: 'var(--surface-2)', borderRadius: 99, overflow: 'hidden', marginBottom: 12 }}>
        <motion.div
          animate={{ width: `${pct}%` }}
          transition={{ duration: 0.08 }}
          style={{
            height: '100%', borderRadius: 99,
            background: results.fail > 0
              ? 'linear-gradient(to right, var(--accent), var(--amber))'
              : 'linear-gradient(to right, var(--accent), #60a5fa)',
          }}
        />
      </div>

      {/* Fire button */}
      <button
        onClick={fire}
        disabled={running}
        style={{
          width: '100%', padding: '8px 0',
          borderRadius: 8, cursor: running ? 'not-allowed' : 'pointer',
          fontSize: 12, fontWeight: 700, letterSpacing: '0.02em',
          background: running
            ? 'var(--surface-1)'
            : 'linear-gradient(135deg, var(--accent) 0%, #60a5fa 100%)',
          color: running ? 'var(--text-3)' : 'white',
          border: 'none',
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
          opacity: running ? 0.7 : 1,
          transition: 'opacity 0.15s, background 0.2s',
        }}
      >
        {running ? (
          <>
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"
              style={{ animation: 'spin 0.8s linear infinite' }}>
              <path d="M21 12a9 9 0 1 1-6.219-8.56"/>
            </svg>
            Firing {progress}/{total}…
          </>
        ) : (
          <>
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
              <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
            </svg>
            Fire {total} Writes
          </>
        )}
      </button>
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
};
