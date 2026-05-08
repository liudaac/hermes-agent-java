import { useState, useEffect } from 'react';
import { tenantApi, type Tenant, type TenantQuota, type TenantUsage, type TenantSecurity } from '../services/api';

interface AuditEvent {
  timestamp: string;
  type: string;
  details: Record<string, unknown>;
}

export function TenantPanel() {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [selectedTenant, setSelectedTenant] = useState<Tenant | null>(null);
  const [quota, setQuota] = useState<TenantQuota | null>(null);
  const [usage, setUsage] = useState<TenantUsage | null>(null);
  const [security, setSecurity] = useState<TenantSecurity | null>(null);
  const [auditLogs, setAuditLogs] = useState<AuditEvent[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [newTenantId, setNewTenantId] = useState('');
  const [activeTab, setActiveTab] = useState<'list' | 'details' | 'quota' | 'security' | 'audit'>('list');
  const [editingQuota, setEditingQuota] = useState(false);
  const [editingSecurity, setEditingSecurity] = useState(false);
  const [editedQuota, setEditedQuota] = useState<Partial<TenantQuota>>({});
  const [editedSecurity, setEditedSecurity] = useState<Partial<TenantSecurity>>({});

  useEffect(() => {
    loadTenants();
  }, []);

  const loadTenants = async () => {
    try {
      setLoading(true);
      const response = await tenantApi.listTenants();
      setTenants(response.data.tenants || []);
      setError(null);
    } catch (err) {
      setError('Failed to load tenants');
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const createTenant = async () => {
    if (!newTenantId.trim()) return;
    try {
      setLoading(true);
      await tenantApi.createTenant(newTenantId);
      setNewTenantId('');
      await loadTenants();
    } catch (err) {
      setError('Failed to create tenant');
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const deleteTenant = async (tenantId: string) => {
    if (!confirm(`Delete tenant "${tenantId}"?`)) return;
    try {
      setLoading(true);
      await tenantApi.deleteTenant(tenantId);
      if (selectedTenant?.id === tenantId) {
        setSelectedTenant(null);
        setActiveTab('list');
      }
      await loadTenants();
    } catch (err) {
      setError('Failed to delete tenant');
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const suspendTenant = async (tenantId: string) => {
    try {
      await tenantApi.suspendTenant(tenantId);
      await loadTenants();
    } catch (err) {
      setError('Failed to suspend tenant');
      console.error(err);
    }
  };

  const resumeTenant = async (tenantId: string) => {
    try {
      await tenantApi.resumeTenant(tenantId);
      await loadTenants();
    } catch (err) {
      setError('Failed to resume tenant');
      console.error(err);
    }
  };

  const viewTenantDetails = async (tenant: Tenant) => {
    setSelectedTenant(tenant);
    setActiveTab('details');
    await loadTenantData(tenant.id);
  };

  const loadTenantData = async (tenantId: string) => {
    try {
      const [quotaRes, usageRes, securityRes] = await Promise.all([
        tenantApi.getQuota(tenantId),
        tenantApi.getUsage(tenantId),
        tenantApi.getSecurity(tenantId),
      ]);
      setQuota(quotaRes.data);
      setUsage(usageRes.data);
      setSecurity(securityRes.data);
      setEditedQuota(quotaRes.data);
      setEditedSecurity(securityRes.data);
    } catch (err) {
      console.error('Failed to load tenant details', err);
    }
  };

  const loadAuditLogs = async () => {
    if (!selectedTenant) return;
    try {
      setLoading(true);
      const response = await tenantApi.getAuditLogs(selectedTenant.id, 100);
      setAuditLogs(response.data.events || []);
    } catch (err) {
      setError('Failed to load audit logs');
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const updateQuota = async () => {
    if (!selectedTenant) return;
    try {
      setLoading(true);
      await tenantApi.updateQuota(selectedTenant.id, editedQuota);
      await loadTenantData(selectedTenant.id);
      setEditingQuota(false);
    } catch (err) {
      setError('Failed to update quota');
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const updateSecurity = async () => {
    if (!selectedTenant) return;
    try {
      setLoading(true);
      await tenantApi.updateSecurity(selectedTenant.id, editedSecurity);
      await loadTenantData(selectedTenant.id);
      setEditingSecurity(false);
    } catch (err) {
      setError('Failed to update security policy');
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const getStateColor = (state: string) => {
    switch (state) {
      case 'ACTIVE': return 'bg-green-100 text-green-800';
      case 'SUSPENDED': return 'bg-yellow-100 text-yellow-800';
      case 'DESTROYED': return 'bg-red-100 text-red-800';
      default: return 'bg-gray-100 text-gray-800';
    }
  };

  const getEventIcon = (type: string) => {
    if (type.includes('CREATE')) return '🆕';
    if (type.includes('DELETE')) return '🗑️';
    if (type.includes('UPDATE')) return '✏️';
    if (type.includes('SUSPEND')) return '⏸️';
    if (type.includes('RESUME')) return '▶️';
    if (type.includes('SECURITY')) return '🔒';
    if (type.includes('NETWORK')) return '🌐';
    if (type.includes('EXEC')) return '⚡';
    return '📝';
  };

  return (
    <div className="bg-white rounded-lg shadow p-6">
      <div className="flex justify-between items-center mb-4">
        <h2 className="text-xl font-bold text-gray-800">Tenant Management</h2>
        <button
          onClick={loadTenants}
          className="px-3 py-1 text-sm bg-gray-100 hover:bg-gray-200 rounded"
          disabled={loading}
        >
          {loading ? 'Loading...' : 'Refresh'}
        </button>
      </div>

      {error && (
        <div className="mb-4 p-3 bg-red-100 text-red-700 rounded">
          {error}
        </div>
      )}

      {/* Create Tenant */}
      <div className="mb-4 flex gap-2">
        <input
          type="text"
          placeholder="New tenant ID"
          value={newTenantId}
          onChange={(e) => setNewTenantId(e.target.value)}
          className="flex-1 px-3 py-2 border rounded"
          onKeyPress={(e) => e.key === 'Enter' && createTenant()}
        />
        <button
          onClick={createTenant}
          disabled={!newTenantId.trim() || loading}
          className="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50"
        >
          Create
        </button>
      </div>

      {/* Tabs */}
      <div className="flex gap-2 mb-4 border-b overflow-x-auto">
        <button
          onClick={() => setActiveTab('list')}
          className={`px-4 py-2 whitespace-nowrap ${activeTab === 'list' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-600'}`}
        >
          Tenants ({tenants.length})
        </button>
        {selectedTenant && (
          <>
            <button
              onClick={() => setActiveTab('details')}
              className={`px-4 py-2 whitespace-nowrap ${activeTab === 'details' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-600'}`}
            >
              Overview
            </button>
            <button
              onClick={() => setActiveTab('quota')}
              className={`px-4 py-2 whitespace-nowrap ${activeTab === 'quota' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-600'}`}
            >
              Quota
            </button>
            <button
              onClick={() => setActiveTab('security')}
              className={`px-4 py-2 whitespace-nowrap ${activeTab === 'security' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-600'}`}
            >
              Security
            </button>
            <button
              onClick={() => { setActiveTab('audit'); loadAuditLogs(); }}
              className={`px-4 py-2 whitespace-nowrap ${activeTab === 'audit' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-600'}`}
            >
              Audit Logs
            </button>
          </>
        )}
      </div>

      {/* Tenant List */}
      {activeTab === 'list' && (
        <div className="space-y-2">
          {tenants.length === 0 ? (
            <p className="text-gray-500 text-center py-4">No tenants found</p>
          ) : (
            tenants.map((tenant) => (
              <div
                key={tenant.id}
                className="p-3 border rounded hover:bg-gray-50 cursor-pointer"
                onClick={() => viewTenantDetails(tenant)}
              >
                <div className="flex justify-between items-start">
                  <div>
                    <div className="font-medium text-gray-900">{tenant.id}</div>
                    <div className="text-sm text-gray-500">
                      Created: {new Date(tenant.createdAt).toLocaleString()}
                    </div>
                    <div className="text-sm text-gray-500">
                      Agents: {tenant.activeAgents} | Sessions: {tenant.activeSessions}
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className={`px-2 py-1 text-xs rounded ${getStateColor(tenant.state)}`}>
                      {tenant.state}
                    </span>
                    {tenant.state === 'ACTIVE' ? (
                      <button
                        onClick={(e) => { e.stopPropagation(); suspendTenant(tenant.id); }}
                        className="text-xs px-2 py-1 bg-yellow-100 text-yellow-700 rounded hover:bg-yellow-200"
                      >
                        Suspend
                      </button>
                    ) : (
                      <button
                        onClick={(e) => { e.stopPropagation(); resumeTenant(tenant.id); }}
                        className="text-xs px-2 py-1 bg-green-100 text-green-700 rounded hover:bg-green-200"
                      >
                        Resume
                      </button>
                    )}
                    <button
                      onClick={(e) => { e.stopPropagation(); deleteTenant(tenant.id); }}
                      className="text-xs px-2 py-1 bg-red-100 text-red-700 rounded hover:bg-red-200"
                    >
                      Delete
                    </button>
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
      )}

      {/* Tenant Details - Overview */}
      {activeTab === 'details' && selectedTenant && (
        <div className="space-y-4">
          <div className="flex justify-between items-center">
            <h3 className="text-lg font-semibold">{selectedTenant.id}</h3>
            <button
              onClick={() => setActiveTab('list')}
              className="text-sm text-blue-600 hover:underline"
            >
              ← Back to list
            </button>
          </div>

          {/* Status Card */}
          <div className="grid grid-cols-2 gap-4">
            <div className="p-3 bg-gray-50 rounded">
              <div className="text-sm text-gray-500">State</div>
              <span className={`px-2 py-1 text-xs rounded ${getStateColor(selectedTenant.state)}`}>
                {selectedTenant.state}
              </span>
            </div>
            <div className="p-3 bg-gray-50 rounded">
              <div className="text-sm text-gray-500">Active Agents</div>
              <div className="text-lg font-semibold">{selectedTenant.activeAgents}</div>
            </div>
            <div className="p-3 bg-gray-50 rounded">
              <div className="text-sm text-gray-500">Active Sessions</div>
              <div className="text-lg font-semibold">{selectedTenant.activeSessions}</div>
            </div>
            <div className="p-3 bg-gray-50 rounded">
              <div className="text-sm text-gray-500">Last Activity</div>
              <div className="text-sm">{new Date(selectedTenant.lastActivity).toLocaleString()}</div>
            </div>
          </div>

          {/* Usage */}
          {usage && (
            <div className="border rounded p-3">
              <h4 className="font-medium mb-2">Resource Usage</h4>
              <div className="space-y-2">
                <div>
                  <div className="flex justify-between text-sm">
                    <span>Storage</span>
                    <span>{(usage.storage / 1024 / 1024).toFixed(2)} MB</span>
                  </div>
                  <div className="w-full bg-gray-200 rounded-full h-2">
                    <div
                      className="bg-blue-600 h-2 rounded-full"
                      style={{ width: `${Math.min((usage.quota.storageUsage / usage.quota.maxStorage) * 100, 100)}%` }}
                    />
                  </div>
                </div>
                <div>
                  <div className="flex justify-between text-sm">
                    <span>Daily Requests</span>
                    <span>{usage.quota.dailyRequests} / {usage.quota.maxDailyRequests}</span>
                  </div>
                  <div className="w-full bg-gray-200 rounded-full h-2">
                    <div
                      className="bg-green-600 h-2 rounded-full"
                      style={{ width: `${Math.min((usage.quota.dailyRequests / usage.quota.maxDailyRequests) * 100, 100)}%` }}
                    />
                  </div>
                </div>
                <div>
                  <div className="flex justify-between text-sm">
                    <span>Daily Tokens</span>
                    <span>{usage.quota.dailyTokens} / {usage.quota.maxDailyTokens}</span>
                  </div>
                  <div className="w-full bg-gray-200 rounded-full h-2">
                    <div
                      className="bg-purple-600 h-2 rounded-full"
                      style={{ width: `${Math.min((usage.quota.dailyTokens / usage.quota.maxDailyTokens) * 100, 100)}%` }}
                    />
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Quota Configuration */}
      {activeTab === 'quota' && selectedTenant && (
        <div className="space-y-4">
          <div className="flex justify-between items-center">
            <h3 className="text-lg font-semibold">Quota Configuration - {selectedTenant.id}</h3>
            <div className="flex gap-2">
              {editingQuota ? (
                <>
                  <button
                    onClick={() => setEditingQuota(false)}
                    className="px-3 py-1 text-sm bg-gray-100 rounded"
                  >
                    Cancel
                  </button>
                  <button
                    onClick={updateQuota}
                    className="px-3 py-1 text-sm bg-green-600 text-white rounded"
                  >
                    Save
                  </button>
                </>
              ) : (
                <button
                  onClick={() => setEditingQuota(true)}
                  className="px-3 py-1 text-sm bg-blue-600 text-white rounded"
                >
                  Edit
                </button>
              )}
            </div>
          </div>

          {quota && (
            <div className="grid grid-cols-2 gap-4">
              {[
                { key: 'maxDailyRequests', label: 'Max Daily Requests', type: 'number' },
                { key: 'maxDailyTokens', label: 'Max Daily Tokens', type: 'number' },
                { key: 'maxConcurrentAgents', label: 'Max Concurrent Agents', type: 'number' },
                { key: 'maxConcurrentSessions', label: 'Max Concurrent Sessions', type: 'number' },
                { key: 'maxStorageBytes', label: 'Max Storage (MB)', type: 'storage' },
                { key: 'maxMemoryBytes', label: 'Max Memory (MB)', type: 'memory' },
                { key: 'requestsPerSecond', label: 'Requests Per Second', type: 'number' },
                { key: 'requestsPerMinute', label: 'Requests Per Minute', type: 'number' },
                { key: 'maxToolCallsPerSession', label: 'Max Tool Calls Per Session', type: 'number' },
                { key: 'maxExecutionTimeSeconds', label: 'Max Execution Time (s)', type: 'number' },
              ].map(({ key, label, type }) => (
                <div key={key} className="p-3 border rounded">
                  <label className="block text-sm text-gray-600 mb-1">{label}</label>
                  {editingQuota ? (
                    <input
                      type="number"
                      value={type === 'storage' || type === 'memory' 
                        ? ((editedQuota[key as keyof TenantQuota] as number) / 1024 / 1024) 
                        : editedQuota[key as keyof TenantQuota] as number}
                      onChange={(e) => {
                        const value = parseInt(e.target.value) || 0;
                        setEditedQuota(prev => ({
                          ...prev,
                          [key]: type === 'storage' || type === 'memory' ? value * 1024 * 1024 : value
                        }));
                      }}
                      className="w-full px-2 py-1 border rounded"
                    />
                  ) : (
                    <div className="font-medium">
                      {type === 'storage' || type === 'memory'
                        ? ((quota[key as keyof TenantQuota] as number) / 1024 / 1024).toFixed(0)
                        : quota[key as keyof TenantQuota]}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Security Policy */}
      {activeTab === 'security' && selectedTenant && (
        <div className="space-y-4">
          <div className="flex justify-between items-center">
            <h3 className="text-lg font-semibold">Security Policy - {selectedTenant.id}</h3>
            <div className="flex gap-2">
              {editingSecurity ? (
                <>
                  <button
                    onClick={() => setEditingSecurity(false)}
                    className="px-3 py-1 text-sm bg-gray-100 rounded"
                  >
                    Cancel
                  </button>
                  <button
                    onClick={updateSecurity}
                    className="px-3 py-1 text-sm bg-green-600 text-white rounded"
                  >
                    Save
                  </button>
                </>
              ) : (
                <button
                  onClick={() => setEditingSecurity(true)}
                  className="px-3 py-1 text-sm bg-blue-600 text-white rounded"
                >
                  Edit
                </button>
              )}
            </div>
          </div>

          {security && (
            <div className="space-y-4">
              {/* Toggles */}
              <div className="grid grid-cols-3 gap-4">
                {[
                  { key: 'allowCodeExecution', label: 'Allow Code Execution' },
                  { key: 'requireSandbox', label: 'Require Sandbox' },
                  { key: 'allowNetworkAccess', label: 'Allow Network Access' },
                ].map(({ key, label }) => (
                  <div key={key} className="p-3 border rounded flex justify-between items-center">
                    <span className="text-sm">{label}</span>
                    {editingSecurity ? (
                      <input
                        type="checkbox"
                        checked={editedSecurity[key as keyof TenantSecurity] as boolean}
                        onChange={(e) => setEditedSecurity(prev => ({
                          ...prev,
                          [key]: e.target.checked
                        }))}
                        className="w-5 h-5"
                      />
                    ) : (
                      <span className={`px-2 py-1 text-xs rounded ${
                        security[key as keyof TenantSecurity] ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
                      }`}>
                        {security[key as keyof TenantSecurity] ? 'Enabled' : 'Disabled'}
                      </span>
                    )}
                  </div>
                ))}
              </div>

              {/* Allowed Languages */}
              <div className="border rounded p-3">
                <label className="block text-sm text-gray-600 mb-2">Allowed Languages</label>
                {editingSecurity ? (
                  <input
                    type="text"
                    value={editedSecurity.allowedLanguages?.join(', ')}
                    onChange={(e) => setEditedSecurity(prev => ({
                      ...prev,
                      allowedLanguages: e.target.value.split(',').map(s => s.trim()).filter(Boolean)
                    }))}
                    className="w-full px-2 py-1 border rounded"
                    placeholder="python, javascript, bash"
                  />
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {security.allowedLanguages?.map(lang => (
                      <span key={lang} className="px-2 py-1 bg-blue-100 text-blue-800 text-xs rounded">
                        {lang}
                      </span>
                    ))}
                  </div>
                )}
              </div>

              {/* Allowed Tools */}
              <div className="border rounded p-3">
                <label className="block text-sm text-gray-600 mb-2">Allowed Tools</label>
                {editingSecurity ? (
                  <input
                    type="text"
                    value={editedSecurity.allowedTools?.join(', ')}
                    onChange={(e) => setEditedSecurity(prev => ({
                      ...prev,
                      allowedTools: e.target.value.split(',').map(s => s.trim()).filter(Boolean)
                    }))}
                    className="w-full px-2 py-1 border rounded"
                    placeholder="read, write, exec"
                  />
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {security.allowedTools?.map(tool => (
                      <span key={tool} className="px-2 py-1 bg-green-100 text-green-800 text-xs rounded">
                        {tool}
                      </span>
                    ))}
                  </div>
                )}
              </div>

              {/* Denied Tools */}
              <div className="border rounded p-3">
                <label className="block text-sm text-gray-600 mb-2">Denied Tools</label>
                {editingSecurity ? (
                  <input
                    type="text"
                    value={editedSecurity.deniedTools?.join(', ')}
                    onChange={(e) => setEditedSecurity(prev => ({
                      ...prev,
                      deniedTools: e.target.value.split(',').map(s => s.trim()).filter(Boolean)
                    }))}
                    className="w-full px-2 py-1 border rounded"
                    placeholder="delete, rm"
                  />
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {security.deniedTools?.map(tool => (
                      <span key={tool} className="px-2 py-1 bg-red-100 text-red-800 text-xs rounded">
                        {tool}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Audit Logs */}
      {activeTab === 'audit' && selectedTenant && (
        <div className="space-y-4">
          <div className="flex justify-between items-center">
            <h3 className="text-lg font-semibold">Audit Logs - {selectedTenant.id}</h3>
            <button
              onClick={loadAuditLogs}
              className="px-3 py-1 text-sm bg-gray-100 hover:bg-gray-200 rounded"
              disabled={loading}
            >
              {loading ? 'Loading...' : 'Refresh'}
            </button>
          </div>

          <div className="space-y-2 max-h-96 overflow-y-auto">
            {auditLogs.length === 0 ? (
              <p className="text-gray-500 text-center py-4">No audit logs found</p>
            ) : (
              auditLogs.map((event, index) => (
                <div key={index} className="p-3 border rounded hover:bg-gray-50">
                  <div className="flex items-start gap-3">
                    <span className="text-2xl">{getEventIcon(event.type)}</span>
                    <div className="flex-1">
                      <div className="flex justify-between items-start">
                        <span className="font-medium text-sm">{event.type}</span>
                        <span className="text-xs text-gray-500">
                          {new Date(event.timestamp).toLocaleString()}
                        </span>
                      </div>
                      <div className="text-xs text-gray-600 mt-1">
                        {Object.entries(event.details).map(([key, value]) => (
                          <div key={key}>
                            <span className="font-medium">{key}:</span> {String(value)}
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
