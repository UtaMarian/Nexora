import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Field,
  Input,
  Text,
} from "@fluentui/react-components";
import { api } from "../api/client";
import type { ColumnDto, NodeDto } from "../api/types";
import { ErrorBar, Loading } from "../components/States";
import { formatDate } from "../components/format";
import { ColumnInput, changedValues } from "../components/columns";

/** Edit name and library column values of a file or folder. */
export function PropertiesDialog({ node, libraryId, onClose }: { node: NodeDto; libraryId: string; onClose: () => void }) {
  const qc = useQueryClient();
  const columns = useQuery({
    queryKey: ["columns", libraryId],
    queryFn: () => api<ColumnDto[]>(`/api/v1/nodes/${libraryId}/columns`),
  });
  const [name, setName] = useState(node.name);
  const [values, setValues] = useState<Record<string, unknown>>({ ...node.metadata });
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const metadata = changedValues(columns.data ?? [], node.metadata ?? {}, values);
      await api(`/api/v1/nodes/${node.id}`, {
        method: "PATCH",
        body: {
          name: name.trim() !== node.name ? name.trim() : undefined,
          metadata: Object.keys(metadata).length > 0 ? metadata : undefined,
        },
      });
      await qc.invalidateQueries();
      onClose();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  const set = (k: string, v: unknown) => setValues((prev) => ({ ...prev, [k]: v }));

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 520 }}>
        <form onSubmit={save}>
          <DialogBody>
            <DialogTitle>Properties</DialogTitle>
            <DialogContent style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <ErrorBar error={error} />
              <Field label="Name" required>
                <Input value={name} onChange={(_, d) => setName(d.value)} />
              </Field>
              {columns.isPending ? (
                <Loading />
              ) : (
                columns.data?.map((c) => (
                  <Field key={c.id} label={c.displayName} required={c.required}>
                    <ColumnInput column={c} value={values[c.internalName]} onChange={(v) => set(c.internalName, v)} />
                  </Field>
                ))
              )}
              <Text size={200}>
                Created {formatDate(node.createdAt)} by {node.createdBy?.displayName} · Modified{" "}
                {formatDate(node.modifiedAt)} by {node.modifiedBy?.displayName}
              </Text>
            </DialogContent>
            <DialogActions>
              <Button onClick={onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={busy || !name.trim()}>
                Save
              </Button>
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}
