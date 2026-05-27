import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectOption } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { RotateCcw } from "lucide-react";

function FieldHint({ schema, schemaKey }: { schema: Record<string, unknown>; schemaKey: string }) {
  const keyPath = schemaKey.includes(".") ? schemaKey : "";
  const description = schema.description ? String(schema.description) : "";

  if (!keyPath && !description) return null;

  return (
    <div className="flex flex-col gap-0.5">
      {keyPath && <span className="text-[10px] font-mono text-muted-foreground/50">{keyPath}</span>}
      {description && <span className="text-xs text-muted-foreground/70">{description}</span>}
    </div>
  );
}

function isDirty(a: unknown, b: unknown): boolean {
  if (a === b) return false;
  if (typeof a !== typeof b) return true;
  if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length) return true;
    return a.some((v, i) => isDirty(v, b[i]));
  }
  if (typeof a === "object" && a !== null && b !== null) {
    const ka = Object.keys(a as object);
    const kb = Object.keys(b as object);
    if (ka.length !== kb.length) return true;
    return ka.some((k) => isDirty((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]));
  }
  return true;
}

export function AutoField({
  schemaKey,
  schema,
  value,
  defaultValue,
  onChange,
  onReset,
}: AutoFieldProps) {
  const rawLabel = schemaKey.split(".").pop() ?? schemaKey;
  const label = rawLabel.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
  const dirty = defaultValue !== undefined && isDirty(value, defaultValue);

  const resetBtn = onReset ? (
    <button
      type="button"
      onClick={onReset}
      className="text-muted-foreground/50 hover:text-muted-foreground transition-colors cursor-pointer"
      title="Reset to default"
    >
      <RotateCcw className="h-3 w-3" />
    </button>
  ) : null;

  if (schema.type === "boolean") {
    return (
      <div className="flex items-center justify-between gap-4">
        <div className="flex flex-col gap-0.5">
          <div className="flex items-center gap-1.5">
            <Label className="text-sm">{label}</Label>
            {dirty && <span className="h-1.5 w-1.5 rounded-full bg-warning" />}
          </div>
          <FieldHint schema={schema} schemaKey={schemaKey} />
        </div>
        <div className="flex items-center gap-2">
          <Switch checked={!!value} onCheckedChange={onChange} />
          {resetBtn}
        </div>
      </div>
    );
  }

  if (schema.type === "select") {
    const options = (schema.options as string[]) ?? [];
    return (
      <div className="grid gap-1.5">
        <div className="flex items-center gap-1.5">
          <Label className="text-sm">{label}</Label>
          {dirty && <span className="h-1.5 w-1.5 rounded-full bg-warning" />}
          <div className="ml-auto">{resetBtn}</div>
        </div>
        <FieldHint schema={schema} schemaKey={schemaKey} />
        <Select value={String(value ?? "")} onValueChange={(v) => onChange(v)}>
          {options.map((opt) => (
            <SelectOption key={opt} value={opt}>
              {opt || "(none)"}
            </SelectOption>
          ))}
        </Select>
      </div>
    );
  }

  if (schema.type === "number") {
    return (
      <div className="grid gap-1.5">
        <div className="flex items-center gap-1.5">
          <Label className="text-sm">{label}</Label>
          {dirty && <span className="h-1.5 w-1.5 rounded-full bg-warning" />}
          <div className="ml-auto">{resetBtn}</div>
        </div>
        <FieldHint schema={schema} schemaKey={schemaKey} />
        <Input
          type="number"
          value={value === undefined || value === null ? "" : String(value)}
          onChange={(e) => {
            const raw = e.target.value;
            if (raw === "") {
              onChange(0);
              return;
            }
            const n = Number(raw);
            if (!Number.isNaN(n)) {
              onChange(n);
            }
          }}
        />
      </div>
    );
  }

  if (schema.type === "text") {
    return (
      <div className="grid gap-1.5">
        <div className="flex items-center gap-1.5">
          <Label className="text-sm">{label}</Label>
          {dirty && <span className="h-1.5 w-1.5 rounded-full bg-warning" />}
          <div className="ml-auto">{resetBtn}</div>
        </div>
        <FieldHint schema={schema} schemaKey={schemaKey} />
        <textarea
          className="flex min-h-[80px] w-full border border-input bg-transparent px-3 py-2 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          value={String(value ?? "")}
          onChange={(e) => onChange(e.target.value)}
        />
      </div>
    );
  }

  if (schema.type === "list") {
    return (
      <div className="grid gap-1.5">
        <div className="flex items-center gap-1.5">
          <Label className="text-sm">{label}</Label>
          {dirty && <span className="h-1.5 w-1.5 rounded-full bg-warning" />}
          <div className="ml-auto">{resetBtn}</div>
        </div>
        <FieldHint schema={schema} schemaKey={schemaKey} />
        <Input
          value={Array.isArray(value) ? value.join(", ") : String(value ?? "")}
          onChange={(e) =>
            onChange(
              e.target.value
                .split(",")
                .map((s) => s.trim())
                .filter(Boolean),
            )
          }
          placeholder="comma-separated values"
        />
      </div>
    );
  }

  if (typeof value === "object" && value !== null && !Array.isArray(value)) {
    const obj = value as Record<string, unknown>;
    const defObj = (defaultValue ?? {}) as Record<string, unknown>;
    return (
      <div className="grid gap-3 border border-border p-3">
        <div className="flex items-center gap-1.5">
          <Label className="text-xs font-medium">{label}</Label>
          {dirty && <span className="h-1.5 w-1.5 rounded-full bg-warning" />}
          <div className="ml-auto">{resetBtn}</div>
        </div>
        <FieldHint schema={schema} schemaKey={schemaKey} />
        {Object.entries(obj).map(([subKey, subVal]) => (
          <div key={subKey} className="grid gap-1">
            <Label className="text-xs text-muted-foreground">{subKey}</Label>
            <Input
              value={String(subVal ?? "")}
              onChange={(e) => onChange({ ...obj, [subKey]: e.target.value })}
              className="text-xs"
            />
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="grid gap-1.5">
      <div className="flex items-center gap-1.5">
        <Label className="text-sm">{label}</Label>
        {dirty && <span className="h-1.5 w-1.5 rounded-full bg-warning" />}
        <div className="ml-auto">{resetBtn}</div>
      </div>
      <FieldHint schema={schema} schemaKey={schemaKey} />
      <Input value={String(value ?? "")} onChange={(e) => onChange(e.target.value)} />
    </div>
  );
}

interface AutoFieldProps {
  schemaKey: string;
  schema: Record<string, unknown>;
  value: unknown;
  defaultValue?: unknown;
  onChange: (v: unknown) => void;
  onReset?: () => void;
}
