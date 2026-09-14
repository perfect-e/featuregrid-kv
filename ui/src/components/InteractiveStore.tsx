import React, { useState } from 'react';

interface InteractiveStoreProps {
  coordinatorUrl?: string;
}

interface LogEntry {
  id: string;
  op: 'PUT' | 'GET' | 'DELETE';
  key: string;
  result: string;
  success: boolean;
  time: string;
}

export const InteractiveStore: React.FC<InteractiveStoreProps> = ({
  coordinatorUrl = 'http://localhost:8080',
}) => {
  const [op, setOp] = useState<'PUT' | 'GET' | 'DELETE'>('PUT');
  const [key, setKey] = useState('');
  const [val, setVal] = useState('');
  const [loading, setLoading] = useState(false);
  const [logs, setLogs] = useState<LogEntry[]>([]);

  const execute = async () => {
    if (!key) return;
    setLoading(true);
    const start = Date.now();
    let success = false;
    let result = '';

    try {
      if (op === 'PUT') {
        const res = await fetch(`${coordinatorUrl}/api/v1/kv/${key}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ value: val, ttlMs: 0 }),
        });
        success = res.ok;
        result = success ? 'OK' : `ERR ${res.status}`;
      } else if (op === 'GET') {
        const res = await fetch(`${coordinatorUrl}/api/v1/kv/${key}`);
        success = res.ok;
        if (success) {
          const data = await res.json();
          result = data.value;
        } else if (res.status === 404) {
          result = 'NOT_FOUND';
          success = true;
        } else {
          result = `ERR ${res.status}`;
        }
      } else if (op === 'DELETE') {
        const res = await fetch(`${coordinatorUrl}/api/v1/kv/${key}`, { method: 'DELETE' });
        success = res.ok;
        result = success ? 'OK' : `ERR ${res.status}`;
      }
    } catch (e: any) {
      result = e.message;
    }

    const ms = Date.now() - start;
    const log: LogEntry = {
      id: crypto.randomUUID(),
      op,
      key,
      result: `${result} (${ms}ms)`,
      success,
      time: new Date().toLocaleTimeString([], { hour12: false, hour: '2-digit', minute:'2-digit', second:'2-digit' }),
    };

    setLogs(prev => [log, ...prev].slice(0, 10)); // keep last 10
    setLoading(false);
  };

  return (
    <div style={{
      background: 'var(--surface-0)', border: '1px solid var(--border)', borderRadius: 12,
      padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 12
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <div style={{
          width: 28, height: 28, borderRadius: 7,
          background: 'var(--surface-1)', border: '1px solid var(--border)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: 'var(--text-2)'
        }}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="4 17 10 11 4 5"></polyline>
            <line x1="12" y1="19" x2="20" y2="19"></line>
          </svg>
        </div>
        <div>
          <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-1)' }}>Interactive Store</div>
          <div style={{ fontSize: 10, color: 'var(--text-3)' }}>manual data operations</div>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <select
          value={op}
          onChange={(e) => setOp(e.target.value as 'PUT' | 'GET' | 'DELETE')}
          style={{
            background: 'var(--surface-1)', border: '1px solid var(--border)', color: 'var(--text-1)',
            padding: '4px 8px', borderRadius: 6, fontSize: 11, fontWeight: 600,
            outline: 'none', cursor: 'pointer'
          }}
        >
          <option value="PUT">PUT</option>
          <option value="GET">GET</option>
          <option value="DELETE">DELETE</option>
        </select>
        
        <input
          placeholder="Key"
          value={key}
          onChange={e => setKey(e.target.value)}
          style={{
            flex: 1, minWidth: 100, background: 'var(--surface-1)', border: '1px solid var(--border)', color: 'var(--text-1)',
            padding: '4px 8px', borderRadius: 6, fontSize: 11, fontFamily: 'var(--font-mono)'
          }}
        />

        {op === 'PUT' && (
          <input
            placeholder="Value"
            value={val}
            onChange={e => setVal(e.target.value)}
            style={{
              flex: 1, minWidth: 100, background: 'var(--surface-1)', border: '1px solid var(--border)', color: 'var(--text-1)',
              padding: '4px 8px', borderRadius: 6, fontSize: 11, fontFamily: 'var(--font-mono)'
            }}
          />
        )}

        <button
          onClick={execute}
          disabled={loading || !key}
          style={{
            background: loading ? 'var(--surface-2)' : 'var(--blue)', 
            color: loading ? 'var(--text-3)' : '#fff',
            border: 'none', padding: '4px 12px', borderRadius: 6, fontSize: 11, fontWeight: 600,
            cursor: loading || !key ? 'not-allowed' : 'pointer',
            transition: 'background 0.2s'
          }}
        >
          {loading ? 'Running...' : 'Execute'}
        </button>
      </div>

      {logs.length > 0 && (
        <div style={{
          background: 'var(--terminal-bg)', border: '1px solid var(--border)', borderRadius: 6,
          padding: '8px', maxHeight: 100, overflowY: 'auto',
          display: 'flex', flexDirection: 'column', gap: 4
        }}>
          {logs.map(l => (
            <div key={l.id} style={{ fontSize: 10, fontFamily: 'var(--font-mono)', display: 'flex', gap: 8 }}>
              <span style={{ color: 'var(--text-4)' }}>{l.time}</span>
              <span style={{ color: 'var(--text-3)', width: 45 }}>{l.op}</span>
              <span style={{ color: 'var(--blue)', width: 80, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{l.key}</span>
              <span style={{ color: l.success ? 'var(--green)' : 'var(--red)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {l.result}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
