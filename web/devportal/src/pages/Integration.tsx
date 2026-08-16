import { useState } from "react";
import { Check, Copy } from "lucide-react";
import { useI18n } from "@/i18n";
import { Card, CardContent, CardHeader, CardTitle } from "@hermes/ui";
import { Badge } from "@hermes/ui";

interface CodeBlockProps {
  code: string;
  lang: string;
}

function CodeBlock({ code, lang }: CodeBlockProps) {
  const [copied, setCopied] = useState(false);
  const handleCopy = () => {
    navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };
  return (
    <div className="relative group">
      <div className="absolute right-2 top-2 opacity-0 group-hover:opacity-100 transition-opacity">
        <button onClick={handleCopy} className="text-muted-foreground hover:text-foreground p-1">
          {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
        </button>
      </div>
      <pre className="bg-code-bg border border-border rounded p-3 font-mono text-xs text-foreground overflow-x-auto">
        <code>{code}</code>
      </pre>
      <div className="absolute right-2 bottom-2">
        <Badge variant="outline" className="text-[9px] font-mono">{lang}</Badge>
      </div>
    </div>
  );
}

export default function Integration() {
  const { t } = useI18n();

  return (
    <div className="flex flex-col gap-6 max-w-4xl">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-foreground">{t.integration.title}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{t.integration.description}</p>
      </div>

      {/* Quick Start */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t.integration.quickStart}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <p className="text-sm text-muted-foreground">
            The Hermes Agent platform exposes a REST API on the dashboard server (default port 9119).
            All endpoints except public status require a session token for authentication.
          </p>
          <CodeBlock lang="bash" code={`# Check platform status
curl http://localhost:9119/api/status

# List sessions
curl -H "Authorization: Bearer $TOKEN" \\
     http://localhost:9119/api/sessions?limit=5`} />
        </CardContent>
      </Card>

      {/* Authentication */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t.integration.authentication}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <p className="text-sm text-muted-foreground">
            The session token is injected into the page as <code className="text-accent-foreground bg-code-bg px-1 rounded">window.__HERMES_SESSION_TOKEN__</code>.
            For API access, pass it as a Bearer token in the Authorization header.
          </p>
          <CodeBlock lang="bash" code={`# Get the session token (from the HTML page)
TOKEN=$(curl -s http://localhost:9119/ | grep -oP '__HERMES_SESSION_TOKEN__="\\K[^"]+')

# Use it in API requests
curl -H "Authorization: Bearer $TOKEN" \\
     http://localhost:9119/api/env`} />
        </CardContent>
      </Card>

      {/* Code Examples */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t.integration.examples}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div>
            <p className="text-xs font-mono text-muted-foreground mb-1">JavaScript (fetch)</p>
            <CodeBlock lang="javascript" code={`const TOKEN = window.__HERMES_SESSION_TOKEN__;
const API = "http://localhost:9119";

// List sessions
const resp = await fetch(\`\${API}/api/sessions?limit=10\`, {
  headers: { Authorization: \`Bearer \${TOKEN}\` },
});
const data = await resp.json();
console.log(data.sessions);

// Send a chat message (SSE stream)
const streamResp = await fetch(\`\${API}/api/chat/stream\`, {
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    Authorization: \`Bearer \${TOKEN}\`,
  },
  body: JSON.stringify({
    message: "Hello!",
    tenant_id: "default",
  }),
});
const reader = streamResp.body!.getReader();
const decoder = new TextDecoder();
while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  console.log(decoder.decode(value));
}`} />
          </div>
          <div>
            <p className="text-xs font-mono text-muted-foreground mb-1">Python (requests)</p>
            <CodeBlock lang="python" code={`import requests

API = "http://localhost:9119"
TOKEN = "your-session-token"
headers = {"Authorization": f"Bearer {TOKEN}"}

# List sessions
resp = requests.get(f"{API}/api/sessions", headers=headers, params={"limit": 10})
sessions = resp.json()["sessions"]
for s in sessions:
    print(s["id"], s.get("title", "Untitled"))

# Set env var
requests.put(
    f"{API}/api/env",
    headers={**headers, "Content-Type": "application/json"},
    json={"key": "ANTHROPIC_API_KEY", "value": "sk-..."},
)

# Create cron job
requests.post(
    f"{API}/api/cron/jobs",
    headers={**headers, "Content-Type": "application/json"},
    json={
        "prompt": "Summarize today's events",
        "schedule": "0 18 * * *",
        "name": "Daily Summary",
    },
)`} />
          </div>
        </CardContent>
      </Card>

      {/* Webhook Setup */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t.integration.webhookSetup}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <p className="text-sm text-muted-foreground">
            Register a webhook URL to receive real-time event notifications. Events are sent as POST requests
            with a JSON body. If a secret is provided, it's used to sign the payload with HMAC-SHA256.
          </p>
          <CodeBlock lang="bash" code={`# Register a webhook
curl -X POST http://localhost:9119/api/v1/webhooks \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer $TOKEN" \\
  -d '{
    "url": "https://example.com/webhook",
    "events": ["message.created", "session.ended"],
    "secret": "my-webhook-secret"
  }'

# List registered webhooks
curl -H "Authorization: Bearer $TOKEN" \\
     http://localhost:9119/api/v1/webhooks`} />
        </CardContent>
      </Card>

      {/* OAuth Setup */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t.integration.oauthSetup}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <p className="text-sm text-muted-foreground">
            OAuth providers can be connected through the DevPortal OAuth page or programmatically.
            Supported flows: PKCE (browser redirect), Device Code, and External CLI.
          </p>
          <CodeBlock lang="bash" code={`# List OAuth providers
curl -H "Authorization: Bearer $TOKEN" \\
     http://localhost:9119/api/providers/oauth

# Start login flow
curl -X POST http://localhost:9119/api/providers/oauth/anthropic/start \\
  -H "Authorization: Bearer $TOKEN"

# Poll for device code status
curl http://localhost:9119/api/providers/oauth/anthropic/poll/$SESSION_ID`} />
        </CardContent>
      </Card>

      {/* API Key Management */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t.integration.apiKeys}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <p className="text-sm text-muted-foreground">
            API keys and secrets are stored in <code className="text-accent-foreground bg-code-bg px-1 rounded">~/.hermes/.env</code>.
            Changes are saved to disk immediately and picked up by active sessions.
          </p>
          <CodeBlock lang="bash" code={`# List all env vars (values are redacted)
curl -H "Authorization: Bearer $TOKEN" \\
     http://localhost:9119/api/env

# Set an API key
curl -X PUT http://localhost:9119/api/env \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer $TOKEN" \\
  -d '{"key": "OPENAI_API_KEY", "value": "sk-..."}'

# Reveal the true value (requires auth)
curl -X POST http://localhost:9119/api/env/reveal \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer $TOKEN" \\
  -d '{"key": "OPENAI_API_KEY"}'

# Delete an env var
curl -X DELETE http://localhost:9119/api/env \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer $TOKEN" \\
  -d '{"key": "OPENAI_API_KEY"}'`} />
        </CardContent>
      </Card>
    </div>
  );
}
