import { useEffect, useState } from 'react';
import { Activity, Server, Clock, CheckCircle, XCircle } from 'lucide-react';
import { agentApi, type AgentStatus } from '../services/api';

export function StatusCard() {
  const [status, setStatus] = useState<AgentStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchStatus();
    const interval = setInterval(fetchStatus, 5000);
    return () => clearInterval(interval);
  }, []);

  const fetchStatus = async () => {
    try {
      const response = await agentApi.getStatus();
      setStatus(response.data);
      setError(null);
    } catch (err) {
      setError('Failed to connect to agent');
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="bg-white rounded-lg shadow-md p-6 animate-pulse">
        <div className="h-4 bg-gray-200 rounded w-1/3 mb-4"></div>
        <div className="h-8 bg-gray-200 rounded w-1/2"></div>
      </div>
    );
  }

  return (
    <div className="bg-white rounded-lg shadow-md p-6">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
          <Activity className="w-5 h-5 text-blue-500" />
          Agent Status
        </h2>
        {status?.connected ? (
          <span className="flex items-center gap-1 text-green-600 text-sm font-medium">
            <CheckCircle className="w-4 h-4" />
            Online
          </span>
        ) : (
          <span className="flex items-center gap-1 text-red-600 text-sm font-medium">
            <XCircle className="w-4 h-4" />
            Offline
          </span>
        )}
      </div>

      {error ? (
        <div className="text-red-500 text-sm">{error}</div>
      ) : (
        <div className="grid grid-cols-2 gap-4">
          <div className="flex items-center gap-3">
            <Server className="w-5 h-5 text-gray-400" />
            <div>
              <p className="text-xs text-gray-500">Version</p>
              <p className="text-sm font-medium text-gray-900">{status?.version || 'Unknown'}</p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <Clock className="w-5 h-5 text-gray-400" />
            <div>
              <p className="text-xs text-gray-500">Uptime</p>
              <p className="text-sm font-medium text-gray-900">
                {status?.uptime ? formatUptime(status.uptime) : 'N/A'}
              </p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function formatUptime(seconds: number): string {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  return `${minutes}m`;
}
