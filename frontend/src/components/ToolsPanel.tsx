import { useEffect, useState } from 'react';
import { Wrench, Terminal, Globe, FileText, Search, Image, Code, Database } from 'lucide-react';
import { agentApi, type ToolInfo } from '../services/api';

const toolIcons: Record<string, React.ReactNode> = {
  terminal: <Terminal className="w-5 h-5" />,
  web_search: <Globe className="w-5 h-5" />,
  file_operations: <FileText className="w-5 h-5" />,
  browser: <Search className="w-5 h-5" />,
  image_generation: <Image className="w-5 h-5" />,
  code_execution: <Code className="w-5 h-5" />,
  memory: <Database className="w-5 h-5" />,
};

export function ToolsPanel() {
  const [tools, setTools] = useState<ToolInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchTools();
  }, []);

  const fetchTools = async () => {
    try {
      const response = await agentApi.getTools();
      setTools(response.data);
      setError(null);
    } catch (err) {
      setError('Failed to load tools');
      // Fallback tools for demo
      setTools([
        { name: 'terminal', description: 'Execute terminal commands', emoji: '💻' },
        { name: 'web_search', description: 'Search the web', emoji: '🔍' },
        { name: 'file_operations', description: 'Read and write files', emoji: '📁' },
        { name: 'browser', description: 'Browse websites', emoji: '🌐' },
        { name: 'image_generation', description: 'Generate images', emoji: '🎨' },
        { name: 'code_execution', description: 'Execute code', emoji: '⚡' },
      ]);
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="bg-white rounded-lg shadow-md p-6">
        <div className="flex items-center gap-2 mb-4">
          <Wrench className="w-5 h-5 text-blue-500" />
          <h2 className="text-lg font-semibold text-gray-900">Available Tools</h2>
        </div>
        <div className="space-y-3">
          {[1, 2, 3].map((i) => (
            <div key={i} className="animate-pulse flex items-center gap-3 p-3 rounded-lg bg-gray-50">
              <div className="w-10 h-10 bg-gray-200 rounded-lg"></div>
              <div className="flex-1">
                <div className="h-4 bg-gray-200 rounded w-24 mb-2"></div>
                <div className="h-3 bg-gray-200 rounded w-32"></div>
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="bg-white rounded-lg shadow-md p-6">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
          <Wrench className="w-5 h-5 text-blue-500" />
          Available Tools
        </h2>
        <span className="text-sm text-gray-500">{tools.length} tools</span>
      </div>

      {error && (
        <div className="text-sm text-amber-600 mb-4 bg-amber-50 p-2 rounded">
          {error}
        </div>
      )}

      <div className="space-y-2">
        {tools.map((tool) => (
          <div
            key={tool.name}
            className="flex items-center gap-3 p-3 rounded-lg bg-gray-50 hover:bg-gray-100 transition-colors cursor-pointer group"
          >
            <div className="w-10 h-10 rounded-lg bg-white border border-gray-200 flex items-center justify-center text-gray-600 group-hover:border-blue-300 group-hover:text-blue-500 transition-colors">
              {toolIcons[tool.name] || <span className="text-lg">{tool.emoji}</span>}
            </div>
            <div className="flex-1 min-w-0">
              <h3 className="text-sm font-medium text-gray-900 capitalize">
                {tool.name.replace('_', ' ')}
              </h3>
              <p className="text-xs text-gray-500 truncate">{tool.description}</p>
            </div>
          </div>
        ))}
      </div>

      <div className="mt-4 pt-4 border-t border-gray-200">
        <p className="text-xs text-gray-500">
          Hermes can use these tools to help you. Just ask in natural language.
        </p>
      </div>
    </div>
  );
}
