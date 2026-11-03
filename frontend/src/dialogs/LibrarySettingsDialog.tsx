import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Divider,
  Dropdown,
  Field,
  Input,
  Option,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Text,
} from "@fluentui/react-components";
import { DeleteRegular } from "@fluentui/react-icons";
import { api } from "../api/client";
import type { ColumnDto, ColumnType, LibrarySettings, NodeDto } from "../api/types";
import { ErrorBar, Loading } from "../components/States";
import { COLUMN_TYPES } from "../components/columns";

const TYPES = COLUMN_TYPES;

export function LibrarySettingsDialog({ library, onClose }: { library: NodeDto; onClose: () => void }) {
  const qc = useQueryClient();
  const isList = library.kind === "LIST";
  const settings = useQuery({
    queryKey: ["settings", library.id],
    queryFn: () => api<LibrarySettings>(`/api/v1/nodes/${library.id}/settings`),
    enabled: !isList,
  });
  const columns = useQuery({
    queryKey: ["columns", library.id],
    queryFn: () => api<ColumnDto[]>(`/api/v1/nodes/${library.id}/columns`),
  });
  const [draft, setDraft] = useState<LibrarySettings | null>(null);
  useEffect(() => {
    if (settings.data && !draft) setDraft(settings.data);
  }, [settings.data, draft]);

  const [colName, setColName] = useState("");
  const [colType, setColType] = useState<ColumnType>("TEXT");
  const [colRequired, setColRequired] = useState(false);
  const [colChoices, setColChoices] = useState("");

  const saveSettings = useMutation({
    mutationFn: (s: LibrarySettings) => api(`/api/v1/nodes/${library.id}/settings`, { method: "PUT", body: s }),
    onSuccess: () => qc.invalidateQueries(),
  });
  const addColumn = useMutation({
    mutationFn: () =>
      api(`/api/v1/nodes/${library.id}/columns`, {
        method: "POST",
        body: {
          displayName: colName,
          type: colType,
          required: colRequired,
          choices: colType === "CHOICE" ? colChoices.split("\n").map((c) => c.trim()).filter(Boolean) : [],
        },
      }),
    onSuccess: () => {
      setColName("");
      setColChoices("");
      setColRequired(false);
      qc.invalidateQueries({ queryKey: ["columns", library.id] });
    },
  });
  const deleteColumn = useMutation({
    mutationFn: (id: string) => api(`/api/v1/nodes/${library.id}/columns/${id}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["columns", library.id] }),
  });

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 720 }}>
        <DialogBody>
          <DialogTitle>{isList ? "List" : "Library"} settings: {library.title ?? library.name}</DialogTitle>
          <DialogContent style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            <ErrorBar error={settings.error ?? saveSettings.error ?? addColumn.error ?? deleteColumn.error} />
            {!isList && (<>
            <Text weight="semibold" size={400}>
              Versioning
            </Text>
            {!draft ? (
              <Loading />
            ) : (
              <>
                <Switch
                  checked={draft.versioningEnabled}
                  onChange={(_, d) => setDraft({ ...draft, versioningEnabled: d.checked })}
                  label="Keep version history"
                />
                <Switch
                  checked={draft.minorVersionsEnabled}
                  disabled={!draft.versioningEnabled}
                  onChange={(_, d) => setDraft({ ...draft, minorVersionsEnabled: d.checked })}
                  label="Create minor (draft) versions that must be published"
                />
                <Switch
                  checked={draft.requireCheckout}
                  onChange={(_, d) => setDraft({ ...draft, requireCheckout: d.checked })}
                  label="Require check out before editing"
                />
                <Field label="Keep at most this many versions" hint="Leave empty to keep all versions">
                  <Input
                    type="number"
                    min={1}
                    value={draft.maxVersions == null ? "" : String(draft.maxVersions)}
                    onChange={(_, d) => setDraft({ ...draft, maxVersions: d.value === "" ? null : Number(d.value) })}
                    style={{ maxWidth: 160 }}
                  />
                </Field>
                <div>
                  <Button appearance="primary" onClick={() => saveSettings.mutate(draft)} disabled={saveSettings.isPending}>
                    {saveSettings.isSuccess ? "Saved" : "Save versioning settings"}
                  </Button>
                </div>
              </>
            )}

            <Divider />
            </>)}
            <Text weight="semibold" size={400}>
              Columns
            </Text>
            {columns.data?.length ? (
              <Table size="small" aria-label="Columns">
                <TableHeader>
                  <TableRow>
                    <TableHeaderCell>Column</TableHeaderCell>
                    <TableHeaderCell>Type</TableHeaderCell>
                    <TableHeaderCell>Required</TableHeaderCell>
                    <TableHeaderCell style={{ width: 48 }} />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {columns.data.map((c) => (
                    <TableRow key={c.id}>
                      <TableCell>{c.displayName}</TableCell>
                      <TableCell>
                        {TYPES.find((t) => t.value === c.type)?.label}
                        {c.type === "CHOICE" && `: ${c.choices.join(", ")}`}
                      </TableCell>
                      <TableCell>{c.required ? "Yes" : "No"}</TableCell>
                      <TableCell>
                        <Button
                          appearance="subtle"
                          size="small"
                          icon={<DeleteRegular />}
                          aria-label={`Delete column ${c.displayName}`}
                          onClick={() =>
                            confirm(`Delete the column "${c.displayName}"? Values stay on items but are hidden.`) &&
                            deleteColumn.mutate(c.id)
                          }
                        />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <Text size={200}>No custom columns yet.</Text>
            )}
            <form
              style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "flex-end" }}
              onSubmit={(e) => {
                e.preventDefault();
                addColumn.mutate();
              }}
            >
              <Field label="New column">
                <Input value={colName} onChange={(_, d) => setColName(d.value)} placeholder="e.g. Department" />
              </Field>
              <Field label="Type">
                <Dropdown
                  value={TYPES.find((t) => t.value === colType)?.label}
                  selectedOptions={[colType]}
                  onOptionSelect={(_, d) => setColType(d.optionValue as ColumnType)}
                  style={{ minWidth: 200 }}
                >
                  {TYPES.map((t) => (
                    <Option key={t.value} value={t.value}>
                      {t.label}
                    </Option>
                  ))}
                </Dropdown>
              </Field>
              <Checkbox checked={colRequired} onChange={(_, d) => setColRequired(!!d.checked)} label="Required" />
              {colType === "CHOICE" && (
                <Field label="Choices (one per line)" style={{ width: "100%" }}>
                  <textarea
                    value={colChoices}
                    onChange={(e) => setColChoices(e.target.value)}
                    rows={3}
                    style={{ font: "inherit", padding: 6 }}
                  />
                </Field>
              )}
              <Button type="submit" disabled={!colName.trim() || addColumn.isPending}>
                Add column
              </Button>
            </form>
          </DialogContent>
          <DialogActions>
            <Button onClick={onClose}>Close</Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}
