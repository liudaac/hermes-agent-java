import { useState, useCallback, type ReactElement, type ReactNode } from "react";
import { Copy, Check } from "lucide-react";
import { cn } from "@hermes/ui";

interface MarkdownRendererProps {
  content: string;
  className?: string;
}

/**
 * Lightweight Markdown renderer for assistant messages.
 * Supports: code blocks, inline code, bold, italic, lists, links, headings.
 * No external deps — uses plain HTML + Tailwind.
 */
export default function MarkdownRenderer({ content, className }: MarkdownRendererProps) {
  const lines = content.split("\n");
  const elements: ReactElement[] = [];
  let i = 0;
  let key = 0;

  while (i < lines.length) {
    const line = lines[i];

    // Code block ```language
    if (line.startsWith("```")) {
      const lang = line.slice(3).trim();
      const codeLines: string[] = [];
      i++;
      while (i < lines.length && !lines[i].startsWith("```")) {
        codeLines.push(lines[i]);
        i++;
      }
      i++; // skip closing ```
      elements.push(
        <CodeBlock key={key++} language={lang} code={codeLines.join("\n")} />,
      );
      continue;
    }

    // Heading
    if (line.startsWith("# ")) {
      elements.push(
        <h3 key={key++} className="text-sm font-bold mt-3 mb-1">
          {renderInline(line.slice(2))}
        </h3>,
      );
      i++;
      continue;
    }
    if (line.startsWith("## ")) {
      elements.push(
        <h4 key={key++} className="text-xs font-bold mt-2 mb-1 opacity-90">
          {renderInline(line.slice(3))}
        </h4>,
      );
      i++;
      continue;
    }

    // Unordered list
    if (line.startsWith("- ") || line.startsWith("* ")) {
      const items: string[] = [];
      while (i < lines.length && (lines[i].startsWith("- ") || lines[i].startsWith("* "))) {
        items.push(lines[i].slice(2));
        i++;
      }
      elements.push(
        <ul key={key++} className="list-disc list-inside space-y-0.5 my-1.5">
          {items.map((item, idx) => (
            <li key={idx} className="text-sm">
              {renderInline(item)}
            </li>
          ))}
        </ul>,
      );
      continue;
    }

    // Ordered list
    if (/^\d+\.\s/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\d+\.\s/.test(lines[i])) {
        items.push(lines[i].replace(/^\d+\.\s/, ""));
        i++;
      }
      elements.push(
        <ol key={key++} className="list-decimal list-inside space-y-0.5 my-1.5">
          {items.map((item, idx) => (
            <li key={idx} className="text-sm">
              {renderInline(item)}
            </li>
          ))}
        </ol>,
      );
      continue;
    }

    // Blockquote
    if (line.startsWith("> ")) {
      const quoteLines: string[] = [];
      while (i < lines.length && lines[i].startsWith("> ")) {
        quoteLines.push(lines[i].slice(2));
        i++;
      }
      elements.push(
        <blockquote
          key={key++}
          className="border-l-2 border-midground/30 pl-3 my-2 opacity-80 italic"
        >
          {renderInline(quoteLines.join(" "))}
        </blockquote>,
      );
      continue;
    }

    // Empty line
    if (line.trim() === "") {
      elements.push(<div key={key++} className="h-2" />);
      i++;
      continue;
    }

    // Regular paragraph
    elements.push(
      <p key={key++} className="text-sm leading-relaxed my-1">
        {renderInline(line)}
      </p>,
    );
    i++;
  }

  return <div className={cn("markdown-body", className)}>{elements}</div>;
}

function CodeBlock({ language, code }: { language: string; code: string }) {
  const [copied, setCopied] = useState(false);

  const copy = useCallback(() => {
    navigator.clipboard.writeText(code).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }, [code]);

  return (
    <div className="my-2 rounded-sm border border-current/10 overflow-hidden bg-black/50">
      <div className="flex items-center justify-between px-2.5 py-1 border-b border-current/10 bg-current/5">
        <span className="text-[10px] opacity-50 font-mono tracking-wider">
          {language || "text"}
        </span>
        <button
          onClick={copy}
          className="flex items-center gap-1 text-[10px] opacity-50 hover:opacity-100 transition-opacity"
        >
          {copied ? (
            <>
              <Check className="h-3 w-3" /> Copied
            </>
          ) : (
            <>
              <Copy className="h-3 w-3" /> Copy
            </>
          )}
        </button>
      </div>
      <pre className="p-3 overflow-x-auto">
        <code className="text-xs font-mono leading-relaxed whitespace-pre">
          {code}
        </code>
      </pre>
    </div>
  );
}

/**
 * Render inline markdown: bold, italic, inline code, links.
 */
function renderInline(text: string): ReactNode {
  const parts: ReactNode[] = [];
  let remaining = text;
  let key = 0;

  const patterns = [
    // Inline code `...`
    {
      regex: /`([^`]+)`/,
      render: (m: RegExpMatchArray) => (
        <code
          key={key++}
          className="bg-current/10 px-1 py-0.5 rounded-sm text-xs font-mono"
        >
          {m[1]}
        </code>
      ),
    },
    // Bold **...**
    {
      regex: /\*\*([^*]+)\*\*/,
      render: (m: RegExpMatchArray) => (
        <strong key={key++} className="font-bold">
          {renderInline(m[1])}
        </strong>
      ),
    },
    // Italic *...* (but not **)
    {
      regex: /\*([^*]+)\*/,
      render: (m: RegExpMatchArray) => (
        <em key={key++} className="italic">
          {renderInline(m[1])}
        </em>
      ),
    },
    // Link [text](url)
    {
      regex: /\[([^\]]+)\]\(([^)]+)\)/,
      render: (m: RegExpMatchArray) => (
        <a
          key={key++}
          href={m[2]}
          target="_blank"
          rel="noopener noreferrer"
          className="underline opacity-80 hover:opacity-100"
        >
          {m[1]}
        </a>
      ),
    },
  ];

  while (remaining.length > 0) {
    let bestMatch: { index: number; length: number; node: ReactNode } | null = null;

    for (const p of patterns) {
      const m = remaining.match(p.regex);
      if (m && m.index !== undefined) {
        if (!bestMatch || m.index < bestMatch.index) {
          bestMatch = {
            index: m.index,
            length: m[0].length,
            node: p.render(m),
          };
        }
      }
    }

    if (!bestMatch) {
      parts.push(<span key={key++}>{remaining}</span>);
      break;
    }

    if (bestMatch.index > 0) {
      parts.push(<span key={key++}>{remaining.slice(0, bestMatch.index)}</span>);
    }
    parts.push(bestMatch.node);
    remaining = remaining.slice(bestMatch.index + bestMatch.length);
  }

  return parts;
}
