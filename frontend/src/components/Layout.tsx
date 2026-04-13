import type { ReactNode } from 'react';
import { Bot, Settings, MessageSquare, Terminal } from 'lucide-react';

interface LayoutProps {
  children: ReactNode;
}

export function Layout({ children }: LayoutProps) {
  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <header className="bg-white border-b border-gray-200 sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex items-center justify-between h-16">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-purple-600 rounded-xl flex items-center justify-center">
                <Bot className="w-6 h-6 text-white" />
              </div>
              <div>
                <h1 className="text-xl font-bold text-gray-900">Hermes</h1>
                <p className="text-xs text-gray-500">AI Agent Dashboard</p>
              </div>
            </div>
            
            <nav className="flex items-center gap-1">
              <NavButton icon={<MessageSquare className="w-4 h-4" />} label="Chat" active />
              <NavButton icon={<Terminal className="w-4 h-4" />} label="Terminal" />
              <NavButton icon={<Settings className="w-4 h-4" />} label="Settings" />
            </nav>

            <div className="flex items-center gap-3">
              <a
                href="https://github.com/liudaac/hermes-agent-java"
                target="_blank"
                rel="noopener noreferrer"
                className="text-gray-400 hover:text-gray-600 transition-colors text-sm"
              >
                GitHub
              </a>
            </div>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
        {children}
      </main>

      {/* Footer */}
      <footer className="bg-white border-t border-gray-200 mt-auto">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
          <div className="flex items-center justify-between text-sm text-gray-500">
            <p>Hermes Agent v0.1.0</p>
            <p>Connected to localhost:8080</p>
          </div>
        </div>
      </footer>
    </div>
  );
}

interface NavButtonProps {
  icon: ReactNode;
  label: string;
  active?: boolean;
}

function NavButton({ icon, label, active }: NavButtonProps) {
  return (
    <button
      className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
        active
          ? 'bg-blue-50 text-blue-600'
          : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
      }`}
    >
      {icon}
      {label}
    </button>
  );
}
