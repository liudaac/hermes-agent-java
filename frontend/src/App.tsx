import { Layout } from './components/Layout';
import { StatusCard } from './components/StatusCard';
import { ChatPanel } from './components/ChatPanel';
import { ToolsPanel } from './components/ToolsPanel';

function App() {
  return (
    <Layout>
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
    </Layout>
  );
}

export default App;
