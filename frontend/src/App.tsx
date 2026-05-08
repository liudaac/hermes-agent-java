import { Layout } from './components/Layout';
import { StatusCard } from './components/StatusCard';
import { ChatPanel } from './components/ChatPanel';
import { ToolsPanel } from './components/ToolsPanel';
import { TenantPanel } from './components/TenantPanel';
import { useState } from 'react';

function App() {
  const [activeView, setActiveView] = useState<'chat' | 'tenants'>('chat');

  return (
    <Layout>
      {/* Navigation */}
      <div className="mb-6 border-b">
        <div className="flex gap-4">
          <button
            onClick={() => setActiveView('chat')}
            className={`px-4 py-2 ${activeView === 'chat' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-600'}`}
          >
            Chat & Tools
          </button>
          <button
            onClick={() => setActiveView('tenants')}
            className={`px-4 py-2 ${activeView === 'tenants' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-600'}`}
          >
            Tenant Management
          </button>
        </div>
      </div>

      {activeView === 'chat' && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Left Column - Status & Tools */}
          <div className="space-y-6">
            <StatusCard />
            <ToolsPanel />
          </div>

          {/* Right Column - Chat */}
          <div className="lg:col-span-2">
            <ChatPanel />
          </div>
        </div>
      )}

      {activeView === 'tenants' && (
        <div className="max-w-4xl mx-auto">
          <TenantPanel />
        </div>
      )}
    </Layout>
  );
}

export default App;
