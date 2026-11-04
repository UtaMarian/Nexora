import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import {
  Button, Dialog, DialogActions, DialogBody, DialogContent, DialogSurface, DialogTitle, Field, Input,
} from "@fluentui/react-components";
import { api, routeFor } from "../api/client";
import type { NodeDto } from "../api/types";
import { ErrorBar } from "../components/States";
import { officeRoute, useOffice } from "../components/office";

export type NewDocumentKind = "WORD" | "EXCEL" | "POWERPOINT";

const LABELS: Record<NewDocumentKind, string> = { WORD: "Word document", EXCEL: "Excel workbook", POWERPOINT: "PowerPoint presentation" };

/** Names a new document, creates it and opens it in the browser editor. */
export function NewDocumentDialog(props: { parent: NodeDto; kind?: NewDocumentKind; template?: NodeDto; onClose: () => void }) {
  const { parent, kind, template } = props;
  const qc = useQueryClient();
  const navigate = useNavigate();
  const office = useOffice();
  const [name, setName] = useState(template ? template.name.replace(/\.[^.]+$/, "") : "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const create = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const doc = await api<NodeDto>(`/api/v1/nodes/${parent.id}/documents`, {
        method: "POST", body: { type: kind, name: name.trim(), templateId: template?.id },
      });
      await qc.invalidateQueries();
      navigate(office.canOpen(doc.name) ? officeRoute(doc.id) : routeFor(doc.path));
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && props.onClose()}>
      <DialogSurface style={{ maxWidth: 440 }}>
        <form onSubmit={create}>
          <DialogBody>
            <DialogTitle>{template ? `New from ${template.title ?? template.name}` : `New ${kind ? LABELS[kind] : "document"}`}</DialogTitle>
            <DialogContent style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <ErrorBar error={error} />
              <Field label="Name" required>
                <Input value={name} onChange={(_, d) => setName(d.value)} autoFocus maxLength={200} />
              </Field>
            </DialogContent>
            <DialogActions>
              <Button onClick={props.onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={busy || !name.trim()}>Create</Button>
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}
