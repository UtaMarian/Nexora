import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Badge, Button, Dialog, DialogActions, DialogBody, DialogContent, DialogSurface, DialogTitle, Dropdown, Field, Input, Option,
  Switch, Tab, TabList, Text, Textarea, makeStyles, tokens,
} from "@fluentui/react-components";
import { AddRegular, DeleteRegular, EditRegular } from "@fluentui/react-icons";
import { api } from "../api/client";
import type { ColumnDto, NodeDto } from "../api/types";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatDate } from "../components/format";
import { PeoplePicker } from "../dialogs/PeoplePicker";
import type {
  ApprovalMode, Rule, RuleAction, RuleActionType, RuleCondition, RuleDefinition, RuleRun, RuleTrigger, RuleWho,
} from "./types";

const useStyles = makeStyles({
  body: { display: "flex", flexDirection: "column", gap: "12px", minHeight: "340px" },
  row: { display: "flex", alignItems: "center", gap: "10px", padding: "8px 0", flexWrap: "wrap", borderBottom: `1px solid ${tokens.colorNeutralStroke3}` },
  grow: { flex: 1, minWidth: "180px", overflowWrap: "anywhere" },
  muted: { color: tokens.colorNeutralForeground3 },
  ideas: { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))", gap: "8px" },
  idea: { justifyContent: "flex-start", textAlign: "left", height: "auto", padding: "10px 12px" },
  section: { display: "flex", flexDirection: "column", gap: "8px", padding: "12px", borderRadius: tokens.borderRadiusLarge, backgroundColor: tokens.colorNeutralBackground2 },
  label: { fontWeight: tokens.fontWeightSemibold, color: tokens.colorBrandForeground1 },
  condition: { display: "grid", gridTemplateColumns: "repeat(3, minmax(0, 1fr)) auto", gap: "6px", alignItems: "center", "& > *": { minWidth: 0 }, "@media (max-width: 640px)": { gridTemplateColumns: "minmax(0, 1fr)" } },
  action: { display: "flex", flexDirection: "column", gap: "8px", padding: "10px", borderRadius: tokens.borderRadiusMedium, backgroundColor: tokens.colorNeutralBackground1 },
  actionHead: { display: "flex", gap: "8px", alignItems: "center" },
  chips: { display: "flex", gap: "6px", flexWrap: "wrap", alignItems: "center" },
});

const TRIGGERS: Record<RuleTrigger, string> = {
  CREATED: "something is added",
  CHANGED: "something is changed",
  APPROVED: "an approval finishes as approved",
  REJECTED: "an approval is rejected",
  DATE: "a date is coming up",
};
const ACTIONS: Record<RuleActionType, string> = {
  REQUEST_APPROVAL: "Ask for approval",
  REQUEST_REVIEW: "Ask for a review",
  SEND_EMAIL: "Send an email",
  SET_VALUE: "Set a column",
  MOVE_TO_FOLDER: "Move to a folder",
  CREATE_ITEM: "Add an item to a list",
};
const OPERATORS: Record<RuleCondition["operator"], string> = {
  eq: "is", ne: "is not", contains: "contains", gt: "is more than", lt: "is less than", notEmpty: "has a value", empty: "is empty",
};
const MODES: Record<ApprovalMode, string> = { ALL: "Everyone must approve", ANY: "The first answer decides", SEQUENTIAL: "One after another" };
const BUILT_IN = [
  { key: "Title", label: "Title" }, { key: "Name", label: "File name" }, { key: "FileType", label: "File type" },
  { key: "CreatedBy", label: "Created by" }, { key: "ModifiedBy", label: "Modified by" },
];

const blank = (type: RuleActionType): RuleAction => {
  switch (type) {
    case "SEND_EMAIL": return { type, people: [], subject: "{title} needs your attention", body: "{by} worked on {title}." };
    case "REQUEST_APPROVAL": case "REQUEST_REVIEW": return { type, people: [], mode: "ALL" };
    case "SET_VALUE": return { type, column: "", value: "" };
    case "MOVE_TO_FOLDER": return { type, folder: "Approved" };
    case "CREATE_ITEM": return { type, list: "", title: "Follow up: {title}" };
  }
};

interface Draft { id?: string; name: string; enabled: boolean; definition: RuleDefinition }

const IDEAS: { label: string; hint: string; make: () => Draft; libraryOnly?: boolean }[] = [
  {
    label: "Approve new documents", hint: "Ask someone to approve everything added here",
    make: () => ({ name: "Approve new documents", enabled: true, definition: { trigger: "CREATED", conditions: [], actions: [blank("REQUEST_APPROVAL")] } }),
  },
  {
    label: "Tell people about changes", hint: "Send an email when something changes",
    make: () => ({ name: "Tell people about changes", enabled: true, definition: { trigger: "CHANGED", conditions: [], actions: [blank("SEND_EMAIL")] } }),
  },
  {
    label: "File what was approved", hint: "Move approved documents to a folder", libraryOnly: true,
    make: () => ({ name: "File approved documents", enabled: true, definition: { trigger: "APPROVED", conditions: [], actions: [blank("MOVE_TO_FOLDER")] } }),
  },
  {
    label: "Remind before a date", hint: "Email a week before a review or renewal date",
    make: () => ({ name: "Remind before a date", enabled: true, definition: { trigger: "DATE", daysBefore: 7, conditions: [], actions: [blank("SEND_EMAIL")] } }),
  },
];

function WhoPicker({ people, columns, onChange }: { people: RuleWho[]; columns: ColumnDto[]; onChange: (p: RuleWho[]) => void }) {
  const s = useStyles();
  const add = (w: RuleWho) => onChange([...people, w]);
  const label = (w: RuleWho) => w.kind === "CREATOR" ? "Who created it" : w.kind === "EDITOR" ? "Who last changed it"
    : w.kind === "COLUMN" ? columns.find((c) => c.internalName === w.column)?.displayName ?? w.column : w.displayName ?? w.login;
  const personColumns = columns.filter((c) => c.type === "PERSON");
  return (
    <Field label="Who">
      <div className={s.chips}>
        {people.map((w, i) => <Button key={i} size="small" title="Remove" onClick={() => onChange(people.filter((_, n) => n !== i))}>{label(w)} ×</Button>)}
      </div>
      <PeoplePicker onSelect={(p) => p && add({ kind: "PERSON", login: p.login, displayName: p.displayName })} />
      <div className={s.chips} style={{ marginTop: 4 }}>
        <Button size="small" appearance="subtle" onClick={() => add({ kind: "CREATOR" })}>+ Who created it</Button>
        <Button size="small" appearance="subtle" onClick={() => add({ kind: "EDITOR" })}>+ Who last changed it</Button>
        {personColumns.map((c) => <Button key={c.id} size="small" appearance="subtle" onClick={() => add({ kind: "COLUMN", column: c.internalName })}>+ {c.displayName}</Button>)}
      </div>
    </Field>
  );
}

function ActionEditor({ action, columns, lists, onChange, onRemove }: {
  action: RuleAction; columns: ColumnDto[]; lists: NodeDto[]; onChange: (a: RuleAction) => void; onRemove: () => void;
}) {
  const s = useStyles();
  const set = (patch: Partial<RuleAction>) => onChange({ ...action, ...patch });
  const settable = columns.filter((c) => c.type !== "PERSON" && c.type !== "LOOKUP");
  const column = settable.find((c) => c.internalName === action.column);
  return (
    <div className={s.action}>
      <div className={s.actionHead}>
        <Text weight="semibold" style={{ flex: 1 }}>{ACTIONS[action.type]}</Text>
        <Button appearance="subtle" size="small" icon={<DeleteRegular />} aria-label={`Remove ${ACTIONS[action.type]}`} onClick={onRemove} />
      </div>
      {(action.type === "SEND_EMAIL" || action.type === "REQUEST_APPROVAL" || action.type === "REQUEST_REVIEW") && (
        <WhoPicker people={action.people ?? []} columns={columns} onChange={(people) => set({ people })} />
      )}
      {action.type === "SEND_EMAIL" && (
        <>
          <Field label="Subject" required><Input value={action.subject ?? ""} onChange={(_, d) => set({ subject: d.value })} /></Field>
          <Field label="Text" hint="You can use {title}, {by} and {link}"><Textarea value={action.body ?? ""} onChange={(_, d) => set({ body: d.value })} /></Field>
        </>
      )}
      {action.type === "REQUEST_APPROVAL" && (
        <Field label="How it works">
          <Dropdown value={MODES[action.mode ?? "ALL"]} selectedOptions={[action.mode ?? "ALL"]} onOptionSelect={(_, d) => set({ mode: d.optionValue as ApprovalMode })}>
            {Object.entries(MODES).map(([k, v]) => <Option key={k} value={k}>{v}</Option>)}
          </Dropdown>
        </Field>
      )}
      {(action.type === "REQUEST_APPROVAL" || action.type === "REQUEST_REVIEW") && (
        <Field label="Due in days (optional)">
          <Input type="number" min={1} max={365} value={action.dueDays ? String(action.dueDays) : ""} onChange={(_, d) => set({ dueDays: d.value ? Number(d.value) : null })} />
        </Field>
      )}
      {action.type === "SET_VALUE" && (
        <>
          <Field label="Column" required>
            <Dropdown value={column?.displayName ?? ""} selectedOptions={action.column ? [action.column] : []} onOptionSelect={(_, d) => set({ column: d.optionValue, value: "" })}>
              {settable.map((c) => <Option key={c.id} value={c.internalName} text={c.displayName}>{c.displayName}</Option>)}
            </Dropdown>
          </Field>
          {column?.type === "CHOICE" ? (
            <Field label="Value">
              <Dropdown value={action.value ?? ""} selectedOptions={action.value ? [action.value] : []} onOptionSelect={(_, d) => set({ value: d.optionValue })}>
                {column.choices.map((c) => <Option key={c} value={c}>{c}</Option>)}
              </Dropdown>
            </Field>
          ) : (
            <Field label="Value"><Input value={action.value ?? ""} onChange={(_, d) => set({ value: d.value })} /></Field>
          )}
        </>
      )}
      {action.type === "MOVE_TO_FOLDER" && (
        <Field label="Folder in this library" hint="It is created if needed. Use / for subfolders" required>
          <Input value={action.folder ?? ""} onChange={(_, d) => set({ folder: d.value })} />
        </Field>
      )}
      {action.type === "CREATE_ITEM" && (
        <>
          <Field label="List" required>
            <Dropdown value={lists.find((l) => l.name === action.list)?.title ?? action.list ?? ""} selectedOptions={action.list ? [action.list] : []} onOptionSelect={(_, d) => set({ list: d.optionValue })}>
              {lists.map((l) => <Option key={l.id} value={l.name} text={l.title ?? l.name}>{l.title ?? l.name}</Option>)}
            </Dropdown>
          </Field>
          <Field label="Title of the new item" hint="You can use {title} and {by}" required>
            <Input value={action.title ?? ""} onChange={(_, d) => set({ title: d.value })} />
          </Field>
        </>
      )}
    </div>
  );
}

function RuleEditor({ container, columns, draft, onDone, onCancel }: { container: NodeDto; columns: ColumnDto[]; draft: Draft; onDone: () => void; onCancel: () => void }) {
  const s = useStyles();
  const qc = useQueryClient();
  const [name, setName] = useState(draft.name);
  const [d, setD] = useState<RuleDefinition>(draft.definition);
  const [error, setError] = useState<unknown>(null);
  const lists = useQuery({ queryKey: ["lists", container.siteId], queryFn: () => api<NodeDto[]>(`/api/v1/sites/${container.siteId}/lists`) });
  const isList = container.kind === "LIST";
  const fields = [...BUILT_IN.filter((b) => !isList || (b.key !== "Name" && b.key !== "FileType")), ...columns.map((c) => ({ key: c.internalName, label: c.displayName }))];
  const label = (key: string) => fields.find((f) => f.key === key)?.label ?? key;
  const dates = columns.filter((c) => c.type === "DATE");
  const choices = (Object.keys(ACTIONS) as RuleActionType[]).filter((t) => t !== "MOVE_TO_FOLDER" || !isList);
  const patchCondition = (i: number, p: Partial<RuleCondition>) => setD({ ...d, conditions: d.conditions.map((c, n) => (n === i ? { ...c, ...p } : c)) });

  const save = async () => {
    setError(null);
    try {
      const body = { name, enabled: draft.enabled, definition: d };
      await api(draft.id ? `/api/v1/rules/${draft.id}` : `/api/v1/nodes/${container.id}/rules`, { method: draft.id ? "PUT" : "POST", body });
      await qc.invalidateQueries({ queryKey: ["rules", container.id] });
      onDone();
    } catch (e) {
      setError(e);
    }
  };

  return (
    <>
      <ErrorBar error={error} />
      <Field label="Rule name" required><Input value={name} onChange={(_, v) => setName(v.value)} maxLength={120} /></Field>
      <div className={s.section}>
        <Text className={s.label}>When</Text>
        <Dropdown aria-label="When" value={TRIGGERS[d.trigger]} selectedOptions={[d.trigger]} onOptionSelect={(_, v) => setD({ ...d, trigger: v.optionValue as RuleTrigger, daysBefore: v.optionValue === "DATE" ? d.daysBefore ?? 7 : null })}>
          {Object.entries(TRIGGERS).map(([k, v]) => <Option key={k} value={k}>{v}</Option>)}
        </Dropdown>
        {d.trigger === "DATE" && (dates.length === 0 ? <Text>This needs a date column. Add one in settings first.</Text> : (
          <div className={s.chips}>
            <Input type="number" min={0} max={365} aria-label="Days before" style={{ width: 90 }} value={String(d.daysBefore ?? 0)} onChange={(_, v) => setD({ ...d, daysBefore: Number(v.value) })} />
            <Text>days before</Text>
            <Dropdown aria-label="Date column" value={label(d.dateColumn ?? "")} selectedOptions={d.dateColumn ? [d.dateColumn] : []} onOptionSelect={(_, v) => setD({ ...d, dateColumn: v.optionValue })}>
              {dates.map((c) => <Option key={c.id} value={c.internalName} text={c.displayName}>{c.displayName}</Option>)}
            </Dropdown>
          </div>
        ))}
      </div>
      <div className={s.section}>
        <Text className={s.label}>Only if</Text>
        {d.conditions.map((c, i) => (
          <div key={i} className={s.condition}>
            <Dropdown aria-label="Column" value={label(c.column)} selectedOptions={[c.column]} onOptionSelect={(_, v) => patchCondition(i, { column: v.optionValue })}>
              {fields.map((f) => <Option key={f.key} value={f.key} text={f.label}>{f.label}</Option>)}
            </Dropdown>
            <Dropdown aria-label="Condition" value={OPERATORS[c.operator]} selectedOptions={[c.operator]} onOptionSelect={(_, v) => patchCondition(i, { operator: v.optionValue as RuleCondition["operator"] })}>
              {Object.entries(OPERATORS).map(([k, v]) => <Option key={k} value={k}>{v}</Option>)}
            </Dropdown>
            <Input aria-label="Value" disabled={c.operator === "empty" || c.operator === "notEmpty"} value={c.value ?? ""} onChange={(_, v) => patchCondition(i, { value: v.value })} />
            <Button appearance="subtle" icon={<DeleteRegular />} aria-label="Remove condition" onClick={() => setD({ ...d, conditions: d.conditions.filter((_, n) => n !== i) })} />
          </div>
        ))}
        <div><Button size="small" icon={<AddRegular />} onClick={() => setD({ ...d, conditions: [...d.conditions, { column: "Title", operator: "contains", value: "" }] })}>Add a condition</Button></div>
      </div>
      <div className={s.section}>
        <Text className={s.label}>Then</Text>
        {d.actions.map((a, i) => (
          <ActionEditor key={i} action={a} columns={columns} lists={lists.data ?? []}
            onChange={(next) => setD({ ...d, actions: d.actions.map((x, n) => (n === i ? next : x)) })}
            onRemove={() => setD({ ...d, actions: d.actions.filter((_, n) => n !== i) })} />
        ))}
        <Dropdown aria-label="Add an action" placeholder="Add an action…" value="" selectedOptions={[]} onOptionSelect={(_, v) => setD({ ...d, actions: [...d.actions, blank(v.optionValue as RuleActionType)] })}>
          {choices.map((t) => <Option key={t} value={t}>{ACTIONS[t]}</Option>)}
        </Dropdown>
      </div>
      <div className={s.chips}>
        <Button appearance="primary" disabled={!name.trim() || !d.actions.length} onClick={save}>Save rule</Button>
        <Button onClick={onCancel}>Back</Button>
      </div>
    </>
  );
}

/** "Automate": the rules of a library or list, and what they did. */
export function RulesDialog({ container, columns, onClose }: { container: NodeDto; columns: ColumnDto[]; onClose: () => void }) {
  const s = useStyles();
  const qc = useQueryClient();
  const [tab, setTab] = useState<"rules" | "runs">("rules");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [error, setError] = useState<unknown>(null);
  const rules = useQuery({ queryKey: ["rules", container.id], queryFn: () => api<Rule[]>(`/api/v1/nodes/${container.id}/rules`) });
  const runs = useQuery({ queryKey: ["rule-runs", container.id], enabled: tab === "runs", queryFn: () => api<RuleRun[]>(`/api/v1/nodes/${container.id}/rule-runs`), refetchInterval: 5000 });
  const ruleName = (id: string) => rules.data?.find((r) => r.id === id)?.name ?? "Rule";

  const act = async (fn: () => Promise<unknown>) => {
    setError(null);
    try {
      await fn();
      await qc.invalidateQueries({ queryKey: ["rules", container.id] });
    } catch (e) {
      setError(e);
    }
  };

  const summary = (r: Rule) => `When ${TRIGGERS[r.definition.trigger]}${r.definition.conditions.length ? ", if conditions hold" : ""}: ${r.definition.actions.map((a) => ACTIONS[a.type].toLowerCase()).join(", then ")}`;

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 680 }}>
        <DialogBody>
          <DialogTitle>Automate {container.title ?? container.name}</DialogTitle>
          <DialogContent className={s.body}>
            <ErrorBar error={error ?? rules.error} />
            {draft ? (
              <RuleEditor container={container} columns={columns} draft={draft} onDone={() => setDraft(null)} onCancel={() => setDraft(null)} />
            ) : (
              <>
                <TabList selectedValue={tab} onTabSelect={(_, d) => setTab(d.value as "rules" | "runs")}>
                  <Tab value="rules">Rules</Tab>
                  <Tab value="runs">What happened</Tab>
                </TabList>
                {tab === "rules" ? (
                  <>
                    {rules.isPending ? <Loading /> : rules.data?.map((r) => (
                      <div key={r.id} className={s.row}>
                        <Switch aria-label={`${r.name} on`} checked={r.enabled}
                          onChange={(_, d) => act(() => api(`/api/v1/rules/${r.id}`, { method: "PUT", body: { name: r.name, enabled: d.checked, definition: r.definition } }))} />
                        <div className={s.grow}>
                          <Text weight="semibold" block>{r.name}</Text>
                          <Text size={200} className={s.muted} block>{summary(r)}</Text>
                        </div>
                        <Button appearance="subtle" size="small" icon={<EditRegular />} aria-label={`Edit ${r.name}`} onClick={() => setDraft(r)} />
                        <Button appearance="subtle" size="small" icon={<DeleteRegular />} aria-label={`Delete ${r.name}`}
                          onClick={() => confirm(`Delete the rule "${r.name}"?`) && act(() => api(`/api/v1/rules/${r.id}`, { method: "DELETE" }))} />
                      </div>
                    ))}
                    <Text weight="semibold">Start from an idea</Text>
                    <div className={s.ideas}>
                      {IDEAS.filter((i) => !i.libraryOnly || container.kind === "LIBRARY").map((i) => (
                        <Button key={i.label} className={s.idea} onClick={() => setDraft(i.make())}>
                          <span><Text weight="semibold" block>{i.label}</Text><Text size={200} className={s.muted} block>{i.hint}</Text></span>
                        </Button>
                      ))}
                    </div>
                    <div>
                      <Button icon={<AddRegular />} onClick={() => setDraft({ name: "", enabled: true, definition: { trigger: "CREATED", conditions: [], actions: [] } })}>Blank rule</Button>
                    </div>
                  </>
                ) : runs.isPending ? <Loading /> : !runs.data?.length ? (
                  <Empty title="Nothing yet"><Text>Each time a rule runs, it appears here.</Text></Empty>
                ) : runs.data.map((r) => (
                  <div key={r.id} className={s.row}>
                    <Badge appearance="tint" color={r.status === "DONE" ? "success" : r.status === "FAILED" ? "danger" : "subtle"}>
                      {r.status === "DONE" ? "Done" : r.status === "FAILED" ? "Failed" : "Skipped"}
                    </Badge>
                    <div className={s.grow}>
                      <Text block>{ruleName(r.ruleId)}: {r.path.split("/").pop()}</Text>
                      <Text size={200} className={s.muted} block>{r.detail}, {formatDate(r.ranAt)}</Text>
                    </div>
                  </div>
                ))}
              </>
            )}
          </DialogContent>
          <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}
