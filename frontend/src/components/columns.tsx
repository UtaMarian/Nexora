import { Checkbox, Dropdown, Input, Option, Persona, Textarea } from "@fluentui/react-components";
import { Link } from "react-router-dom";
import { routeFor } from "../api/client";
import type { ColumnDto, ColumnType, PersonValue, PrincipalRef } from "../api/types";
import { PeoplePicker } from "../dialogs/PeoplePicker";

export const COLUMN_TYPES: { value: ColumnType; label: string }[] = [
  { value: "TEXT", label: "Single line of text" },
  { value: "NOTE", label: "Multiple lines of text" },
  { value: "NUMBER", label: "Number" },
  { value: "DATE", label: "Date" },
  { value: "BOOLEAN", label: "Yes/No" },
  { value: "CHOICE", label: "Choice" },
  { value: "URL", label: "Hyperlink" },
  { value: "PERSON", label: "Person or group" },
];

const dateFmt = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });

const isPerson = (v: unknown): v is PersonValue => !!v && typeof v === "object" && "displayName" in (v as object);

/** Plain-text rendering of a column value (sorting, filtering, compact cells). */
export function valueText(v: unknown): string {
  if (v == null) return "";
  if (typeof v === "boolean") return v ? "Yes" : "No";
  if (isPerson(v)) return v.displayName;
  if (typeof v === "string" && /^\d{4}-\d{2}-\d{2}/.test(v)) {
    const d = new Date(v.length === 10 ? v + "T00:00:00" : v);
    return isNaN(d.getTime()) ? v : dateFmt.format(d);
  }
  return String(v);
}

/** Rich rendering for table cells: links are clickable, people show as personas. */
export function ColumnValue({ column, value }: { column?: ColumnDto; value: unknown }) {
  if (value == null || value === "") return null;
  if (isPerson(value)) return <Persona name={value.displayName} size="extra-small" avatar={{ color: "colorful" }} />;
  if (column?.type === "URL" && typeof value === "string") {
    return value.startsWith("/") ? (
      <Link to={routeFor(value)}>{value}</Link>
    ) : (
      <a href={value} target="_blank" rel="noopener noreferrer">
        {value.replace(/^https?:\/\//, "")}
      </a>
    );
  }
  return <>{valueText(value)}</>;
}

export function ColumnInput({ column, value, onChange }: { column: ColumnDto; value: unknown; onChange: (v: unknown) => void }) {
  const str = value == null || typeof value === "object" ? "" : String(value);
  switch (column.type) {
    case "NOTE":
      return <Textarea value={str} onChange={(_, d) => onChange(d.value)} resize="vertical" />;
    case "NUMBER":
      return <Input type="number" value={str} onChange={(_, d) => onChange(d.value === "" ? null : d.value)} />;
    case "DATE":
      return <Input type="date" value={str.slice(0, 10)} onChange={(_, d) => onChange(d.value || null)} />;
    case "BOOLEAN":
      return <Checkbox checked={value === true} onChange={(_, d) => onChange(!!d.checked)} label="Yes" />;
    case "URL":
      return <Input type="url" value={str} placeholder="https://..." onChange={(_, d) => onChange(d.value)} />;
    case "PERSON":
      return (
        <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
          {isPerson(value) && <Persona name={value.displayName} secondaryText={value.login} size="small" />}
          <PeoplePicker
            placeholder={isPerson(value) ? "Change…" : "Enter a name"}
            onSelect={(p: PrincipalRef | null) => onChange(p ? { id: p.id, login: p.login, displayName: p.displayName } : null)}
          />
          {isPerson(value) && !column.required && (
            <button type="button" onClick={() => onChange(null)} style={{ background: "none", border: "none", cursor: "pointer", color: "inherit", textDecoration: "underline" }}>
              Clear
            </button>
          )}
        </div>
      );
    case "CHOICE":
      return (
        <Dropdown
          value={str}
          selectedOptions={str ? [str] : []}
          onOptionSelect={(_, d) => onChange(d.optionValue === "" ? null : d.optionValue)}
          placeholder="Choose…"
        >
          {!column.required && <Option value="">(none)</Option>}
          {column.choices.map((ch) => (
            <Option key={ch} value={ch}>
              {ch}
            </Option>
          ))}
        </Dropdown>
      );
    default:
      return <Input value={str} maxLength={255} onChange={(_, d) => onChange(d.value)} />;
  }
}

/** Values to send for an edit: only changed fields; people are sent by id. */
export function changedValues(columns: ColumnDto[], before: Record<string, unknown>, after: Record<string, unknown>) {
  const out: Record<string, unknown> = {};
  for (const c of columns) {
    const a = after[c.internalName] ?? null;
    const b = before[c.internalName] ?? null;
    if (JSON.stringify(a) !== JSON.stringify(b)) out[c.internalName] = isPerson(a) ? a.id : a;
  }
  return out;
}
