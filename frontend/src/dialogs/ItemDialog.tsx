import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
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
import { can } from "../components/me";
import { ColumnInput, changedValues } from "../components/columns";
import { ErrorBar } from "../components/States";
import { formatDate } from "../components/format";
import { ItemExtras } from "../lists/ItemExtras";

/** New / edit form for a list item. Read-only for people without edit rights. */
export function ItemDialog(props: { list: NodeDto; columns: ColumnDto[]; item?: NodeDto; onClose: () => void }) {
  const { list, columns, item } = props;
  const qc = useQueryClient();
  const editable = item ? can(item, "EDIT_ITEMS") : can(list, "ADD_ITEMS");
  const [title, setTitle] = useState(item?.title ?? "");
  const [values, setValues] = useState<Record<string, unknown>>({ ...(item?.metadata ?? {}) });
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const changed = changedValues(columns, item?.metadata ?? {}, values);
      if (item) {
        await api(`/api/v1/nodes/${item.id}`, {
          method: "PATCH",
          body: { title: title !== item.title ? title : undefined, metadata: Object.keys(changed).length ? changed : undefined },
        });
      } else {
        await api(`/api/v1/nodes/${list.id}/items`, { method: "POST", body: { title, values: changed } });
      }
      await qc.invalidateQueries();
      props.onClose();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && props.onClose()}>
      <DialogSurface style={{ maxWidth: 560 }}>
        <form onSubmit={save}>
          <DialogBody>
            <DialogTitle>{item ? (editable ? "Edit item" : "Item") : `New item in ${list.title ?? list.name}`}</DialogTitle>
            <DialogContent style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <ErrorBar error={error} />
              <fieldset disabled={!editable} style={{ border: "none", padding: 0, margin: 0, display: "flex", flexDirection: "column", gap: 12 }}>
                <Field label="Title" required>
                  <Input value={title} onChange={(_, d) => setTitle(d.value)} maxLength={255} autoFocus />
                </Field>
                {columns.map((c) => (
                  <Field key={c.id} label={c.displayName} required={c.required}>
                    <ColumnInput column={c} value={values[c.internalName]}
                      onChange={(v) => setValues((prev) => ({ ...prev, [c.internalName]: v }))} />
                  </Field>
                ))}
              </fieldset>
              {item && (
                <Text size={200}>
                  Item {item.name} · Created {formatDate(item.createdAt)} by {item.createdBy?.displayName} · Modified{" "}
                  {formatDate(item.modifiedAt)} by {item.modifiedBy?.displayName}
                </Text>
              )}
              {item && <ItemExtras item={item} columns={columns} onRestored={props.onClose} />}
            </DialogContent>
            <DialogActions>
              <Button onClick={props.onClose}>{editable ? "Cancel" : "Close"}</Button>
              {editable && (
                <Button appearance="primary" type="submit" disabled={busy || !title.trim()}>
                  Save
                </Button>
              )}
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}
