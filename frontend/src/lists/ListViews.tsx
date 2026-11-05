import { useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button, Card, Checkbox, Dialog, DialogActions, DialogBody, DialogContent, DialogSurface, DialogTitle, Dropdown, Field, Input,
  Option, Switch, Text, makeStyles, mergeClasses, tokens,
} from "@fluentui/react-components";
import { AddRegular, ChevronLeftRegular, ChevronRightRegular, DeleteRegular } from "@fluentui/react-icons";
import { api, contentUrl } from "../api/client";
import type { ColumnDto, ListView, ListViewFilter, ListViewType, NodeDto } from "../api/types";
import { ColumnValue, valueText } from "../components/columns";
import { ErrorBar } from "../components/States";

export const BUILT_IN: { key: string; label: string }[] = [
  { key: "Title", label: "Title" }, { key: "Modified", label: "Modified" }, { key: "ModifiedBy", label: "Modified by" },
  { key: "Created", label: "Created" }, { key: "CreatedBy", label: "Created by" },
];

const OPERATORS: { value: ListViewFilter["operator"]; label: string }[] = [
  { value: "eq", label: "is" }, { value: "ne", label: "is not" }, { value: "contains", label: "contains" },
  { value: "gt", label: "is greater than" }, { value: "lt", label: "is less than" },
  { value: "notEmpty", label: "has a value" }, { value: "empty", label: "is empty" },
];

/** A value for sorting and filtering: built-in columns, then the item's own values. */
export function cellValue(n: NodeDto, key: string): string | number {
  switch (key) {
    case "Title": return n.title ?? "";
    case "Modified": return n.modifiedAt;
    case "Created": return n.createdAt;
    case "ModifiedBy": return n.modifiedBy?.displayName ?? "";
    case "CreatedBy": return n.createdBy?.displayName ?? "";
  }
  const v = n.metadata?.[key];
  return typeof v === "number" ? v : typeof v === "string" ? v : valueText(v);
}

function matches(n: NodeDto, f: ListViewFilter): boolean {
  const raw = cellValue(n, f.column);
  const text = String(raw).toLowerCase();
  const wanted = (f.value ?? "").toLowerCase();
  const compare = typeof raw === "number" && wanted !== "" && !isNaN(Number(wanted))
    ? raw - Number(wanted) : text.localeCompare(wanted);
  switch (f.operator) {
    case "eq": return text === wanted;
    case "ne": return text !== wanted;
    case "contains": return text.includes(wanted);
    case "gt": return compare > 0;
    case "lt": return compare < 0;
    case "empty": return text === "";
    case "notEmpty": return text !== "";
  }
}

export function applyView(items: NodeDto[], view: ListView | null): NodeDto[] {
  if (!view) return items;
  const out = items.filter((n) => (view.filters ?? []).every((f) => matches(n, f)));
  if (view.sortBy) {
    const dir = view.sortDescending ? -1 : 1;
    out.sort((a, b) => {
      const x = cellValue(a, view.sortBy!);
      const y = cellValue(b, view.sortBy!);
      if (x === "" && y !== "") return 1;
      if (y === "" && x !== "") return -1;
      return (x < y ? -1 : x > y ? 1 : 0) * dir;
    });
  }
  return out;
}

const useStyles = makeStyles({
  month: { display: "flex", alignItems: "center", gap: "8px", padding: "8px" },
  grid: { display: "grid", gridTemplateColumns: "repeat(7, minmax(0, 1fr))", borderTop: `1px solid ${tokens.colorNeutralStroke2}` },
  dayName: { padding: "6px 8px", fontWeight: tokens.fontWeightSemibold, color: tokens.colorNeutralForeground3, fontSize: tokens.fontSizeBase200, "@media (max-width: 640px)": { padding: "4px 2px", textAlign: "center" } },
  day: { minHeight: "104px", padding: "4px", borderTop: `1px solid ${tokens.colorNeutralStroke3}`, borderRight: `1px solid ${tokens.colorNeutralStroke3}`, display: "flex", flexDirection: "column", gap: "2px", minWidth: 0, "@media (max-width: 640px)": { minHeight: "64px", padding: "2px" } },
  outside: { backgroundColor: tokens.colorNeutralBackground2, color: tokens.colorNeutralForeground4 },
  today: { color: tokens.colorBrandForeground1, fontWeight: tokens.fontWeightBold },
  chip: {
    border: "none", borderRadius: tokens.borderRadiusSmall, padding: "1px 6px", textAlign: "left", cursor: "pointer", font: "inherit", fontSize: tokens.fontSizeBase200,
    backgroundColor: tokens.colorBrandBackground2, color: tokens.colorBrandForeground2, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap",
    ":hover": { backgroundColor: tokens.colorBrandBackground2Hover },
  },
  gallery: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: "12px" },
  tile: { padding: 0, overflow: "hidden", cursor: "pointer" },
  cover: { width: "100%", aspectRatio: "16 / 10", objectFit: "cover", display: "block" },
  noCover: {
    width: "100%", aspectRatio: "16 / 10", display: "flex", alignItems: "center", justifyContent: "center", fontSize: "40px", fontWeight: tokens.fontWeightSemibold,
    background: `linear-gradient(135deg, ${tokens.colorBrandBackground} 0%, ${tokens.colorBrandBackgroundPressed} 100%)`, color: tokens.colorNeutralForegroundOnBrand,
  },
  tileBody: { display: "flex", flexDirection: "column", gap: "4px", padding: "10px 12px 12px" },
  line: { display: "flex", gap: "6px", flexWrap: "wrap", color: tokens.colorNeutralForeground2, fontSize: tokens.fontSizeBase200 },
  label: { color: tokens.colorNeutralForeground3 },
  form: { display: "flex", flexDirection: "column", gap: "12px" },
  columnsBox: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(170px, 1fr))", gap: "2px" },
  filter: { display: "grid", gridTemplateColumns: "repeat(3, minmax(0, 1fr)) auto", gap: "6px", alignItems: "center", "& > *": { minWidth: 0 }, "@media (max-width: 640px)": { gridTemplateColumns: "minmax(0, 1fr)" } },
});

const DAY_NAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const dayKey = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/** A month of items, on their date column, or across it and an end date. */
export function CalendarView(props: { items: NodeDto[]; view: ListView; onOpen: (n: NodeDto) => void }) {
  const s = useStyles();
  const { items, view } = props;
  const firstDated = useMemo(() => {
    const today = dayKey(new Date());
    const dates = items.map((n) => String(n.metadata?.[view.dateColumn!] ?? "").slice(0, 10)).filter(Boolean).sort();
    return dates.find((d) => d >= today) ?? dates[dates.length - 1] ?? today;
  }, [items, view.dateColumn]);
  const [offset, setOffset] = useState(0);
  const base = new Date(`${firstDated}T00:00:00`);
  const month = new Date(base.getFullYear(), base.getMonth() + offset, 1);

  const byDay = useMemo(() => {
    const map = new Map<string, NodeDto[]>();
    for (const n of items) {
      const start = String(n.metadata?.[view.dateColumn!] ?? "").slice(0, 10);
      if (!start) continue;
      const end = view.endColumn ? String(n.metadata?.[view.endColumn] ?? "").slice(0, 10) : "";
      const d = new Date(`${start}T00:00:00`);
      const last = new Date(`${end > start ? end : start}T00:00:00`);
      for (let i = 0; i < 62 && d <= last; i++) {
        const key = dayKey(d);
        map.set(key, [...(map.get(key) ?? []), n]);
        d.setDate(d.getDate() + 1);
      }
    }
    return map;
  }, [items, view.dateColumn, view.endColumn]);

  const first = new Date(month);
  first.setDate(1 - ((month.getDay() + 6) % 7));
  const today = dayKey(new Date());
  const cells = Array.from({ length: 42 }, (_, i) => new Date(first.getFullYear(), first.getMonth(), first.getDate() + i));
  const weeks = cells[35].getMonth() === month.getMonth() ? cells : cells.slice(0, 35);

  return (
    <div>
      <div className={s.month}>
        <Button appearance="subtle" icon={<ChevronLeftRegular />} aria-label="Previous month" onClick={() => setOffset((o) => o - 1)} />
        <Button appearance="subtle" icon={<ChevronRightRegular />} aria-label="Next month" onClick={() => setOffset((o) => o + 1)} />
        <Text size={500} weight="semibold">{month.toLocaleDateString(undefined, { month: "long", year: "numeric" })}</Text>
      </div>
      <div className={s.grid}>
        {DAY_NAMES.map((d) => <div key={d} className={s.dayName}>{d}</div>)}
        {weeks.map((d) => {
          const key = dayKey(d);
          return (
            <div key={key} className={mergeClasses(s.day, d.getMonth() !== month.getMonth() && s.outside)}>
              <Text size={200} className={key === today ? s.today : undefined}>{d.getDate()}</Text>
              {(byDay.get(key) ?? []).map((n) => (
                <button key={n.id} type="button" className={s.chip} title={n.title} onClick={() => props.onOpen(n)}>{n.title}</button>
              ))}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/** Items as cards, with the first attached image as the cover. */
export function GalleryView(props: { list: NodeDto; items: NodeDto[]; columns: ColumnDto[]; view: ListView; onOpen: (n: NodeDto) => void }) {
  const s = useStyles();
  const covers = useQuery({ queryKey: ["covers", props.list.id], queryFn: () => api<Record<string, string>>(`/api/v1/nodes/${props.list.id}/covers`) });
  const shown = props.view.columns?.length
    ? props.columns.filter((c) => props.view.columns.includes(c.internalName))
    : props.columns.filter((c) => c.type !== "NOTE").slice(0, 3);
  return (
    <div className={s.gallery}>
      {props.items.map((n) => {
        const cover = covers.data?.[n.id];
        return (
          <Card key={n.id} className={s.tile} onClick={() => props.onOpen(n)}>
            {cover ? <img src={contentUrl(cover)} alt="" className={s.cover} /> : <div className={s.noCover} aria-hidden>{(n.title ?? "?").slice(0, 1).toUpperCase()}</div>}
            <div className={s.tileBody}>
              <Text weight="semibold">{n.title}</Text>
              {shown.filter((c) => n.metadata?.[c.internalName] != null).map((c) => (
                <div key={c.id} className={s.line}>
                  <span className={s.label}>{c.displayName}:</span>
                  <ColumnValue column={c} value={n.metadata?.[c.internalName]} />
                </div>
              ))}
            </div>
          </Card>
        );
      })}
    </div>
  );
}

const TYPES: { value: ListViewType; label: string }[] = [
  { value: "table", label: "Table" }, { value: "calendar", label: "Calendar" }, { value: "gallery", label: "Gallery" },
];

/** Creates or changes a saved view. */
export function ViewDialog(props: { list: NodeDto; columns: ColumnDto[]; view?: ListView; onSaved: (v: ListView) => void; onDeleted: () => void; onClose: () => void }) {
  const s = useStyles();
  const qc = useQueryClient();
  const { view, columns, list } = props;
  const dates = columns.filter((c) => c.type === "DATE");
  const all = [...BUILT_IN, ...columns.map((c) => ({ key: c.internalName, label: c.displayName }))];
  const label = (key?: string | null) => all.find((c) => c.key === key)?.label ?? "";

  const [name, setName] = useState(view?.name ?? "");
  const [type, setType] = useState<ListViewType>(view?.type ?? "table");
  const [shown, setShown] = useState<string[]>(view?.columns?.length ? view.columns : ["Title", ...columns.slice(0, 4).map((c) => c.internalName)]);
  const [sortBy, setSortBy] = useState(view?.sortBy ?? "");
  const [descending, setDescending] = useState(view?.sortDescending ?? false);
  const [filters, setFilters] = useState<ListViewFilter[]>(view?.filters ?? []);
  const [dateColumn, setDateColumn] = useState(view?.dateColumn ?? dates[0]?.internalName ?? "");
  const [endColumn, setEndColumn] = useState(view?.endColumn ?? "");
  const [isDefault, setDefault] = useState(view?.isDefault ?? false);
  const [error, setError] = useState<unknown>(null);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    const body = { name, type, columns: shown, sortBy: sortBy || null, sortDescending: descending, filters, dateColumn: dateColumn || null, endColumn: endColumn || null, isDefault };
    try {
      const saved = await api<ListView>(view ? `/api/v1/nodes/${list.id}/views/${view.id}` : `/api/v1/nodes/${list.id}/views`, { method: view ? "PUT" : "POST", body });
      await qc.invalidateQueries({ queryKey: ["views", list.id] });
      props.onSaved(saved);
    } catch (err) {
      setError(err);
    }
  };

  const remove = async () => {
    if (!view || !confirm(`Delete the view "${view.name}"?`)) return;
    await api(`/api/v1/nodes/${list.id}/views/${view.id}`, { method: "DELETE" });
    await qc.invalidateQueries({ queryKey: ["views", list.id] });
    props.onDeleted();
  };

  const patch = (i: number, change: Partial<ListViewFilter>) => setFilters((f) => f.map((x, n) => (n === i ? { ...x, ...change } : x)));

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && props.onClose()}>
      <DialogSurface style={{ maxWidth: 640 }}>
        <form onSubmit={save}>
          <DialogBody>
            <DialogTitle>{view ? `Edit ${view.name}` : "New view"}</DialogTitle>
            <DialogContent className={s.form}>
              <ErrorBar error={error} />
              <Field label="Name" required><Input value={name} onChange={(_, d) => setName(d.value)} maxLength={60} autoFocus /></Field>
              <Field label="Show as">
                <Dropdown value={TYPES.find((t) => t.value === type)?.label} selectedOptions={[type]} onOptionSelect={(_, d) => setType(d.optionValue as ListViewType)}>
                  {TYPES.map((t) => <Option key={t.value} value={t.value}>{t.label}</Option>)}
                </Dropdown>
              </Field>
              {type === "calendar" ? (
                dates.length === 0 ? <Text>A calendar needs a date column. Add one in List settings first.</Text> : (
                  <>
                    <Field label="Date column" required>
                      <Dropdown value={label(dateColumn)} selectedOptions={[dateColumn]} onOptionSelect={(_, d) => setDateColumn(d.optionValue ?? "")}>
                        {dates.map((c) => <Option key={c.id} value={c.internalName}>{c.displayName}</Option>)}
                      </Dropdown>
                    </Field>
                    <Field label="End date column (optional)">
                      <Dropdown value={endColumn ? label(endColumn) : "None"} selectedOptions={[endColumn]} onOptionSelect={(_, d) => setEndColumn(d.optionValue ?? "")}>
                        <Option value="">None</Option>
                        {dates.map((c) => <Option key={c.id} value={c.internalName}>{c.displayName}</Option>)}
                      </Dropdown>
                    </Field>
                  </>
                )
              ) : (
                <Field label="Columns">
                  <div className={s.columnsBox}>
                    {all.map((c) => (
                      <Checkbox key={c.key} label={c.label} checked={shown.includes(c.key)}
                        onChange={(_, d) => setShown((list_) => (d.checked ? [...list_, c.key] : list_.filter((k) => k !== c.key)))} />
                    ))}
                  </div>
                </Field>
              )}
              {type !== "calendar" && (
                <Field label="Sort by">
                  <div style={{ display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap" }}>
                    <Dropdown value={sortBy ? label(sortBy) : "Newest first"} selectedOptions={[sortBy]} onOptionSelect={(_, d) => setSortBy(d.optionValue ?? "")}>
                      <Option value="">Newest first</Option>
                      {all.map((c) => <Option key={c.key} value={c.key}>{c.label}</Option>)}
                    </Dropdown>
                    {sortBy && <Switch label="Descending" checked={descending} onChange={(_, d) => setDescending(d.checked)} />}
                  </div>
                </Field>
              )}
              <div>
                <Text weight="semibold" block style={{ marginBottom: 6 }}>Only show items where</Text>
                {filters.map((f, i) => (
                  <div key={i} className={s.filter} style={{ marginBottom: 6 }}>
                    <Dropdown aria-label="Column" value={label(f.column)} selectedOptions={[f.column]} onOptionSelect={(_, d) => patch(i, { column: d.optionValue ?? "" })}>
                      {all.map((c) => <Option key={c.key} value={c.key}>{c.label}</Option>)}
                    </Dropdown>
                    <Dropdown aria-label="Condition" value={OPERATORS.find((o) => o.value === f.operator)?.label} selectedOptions={[f.operator]}
                      onOptionSelect={(_, d) => patch(i, { operator: d.optionValue as ListViewFilter["operator"] })}>
                      {OPERATORS.map((o) => <Option key={o.value} value={o.value}>{o.label}</Option>)}
                    </Dropdown>
                    <Input aria-label="Value" disabled={f.operator === "empty" || f.operator === "notEmpty"} value={f.value ?? ""} onChange={(_, d) => patch(i, { value: d.value })} />
                    <Button appearance="subtle" icon={<DeleteRegular />} aria-label="Remove condition" onClick={() => setFilters((l) => l.filter((_, n) => n !== i))} />
                  </div>
                ))}
                <Button size="small" icon={<AddRegular />} onClick={() => setFilters((l) => [...l, { column: "Title", operator: "contains", value: "" }])}>Add condition</Button>
              </div>
              <Switch label="Open this view by default" checked={isDefault} onChange={(_, d) => setDefault(d.checked)} />
            </DialogContent>
            <DialogActions>
              {view && <Button onClick={remove}>Delete view</Button>}
              <Button onClick={props.onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={!name.trim() || (type === "calendar" && !dateColumn)}>Save</Button>
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}
