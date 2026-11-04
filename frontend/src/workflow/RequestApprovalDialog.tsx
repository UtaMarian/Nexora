import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import {
  Button, Dialog, DialogActions, DialogBody, DialogContent, DialogSurface, DialogTitle, Dropdown, Field, Input, Option, Textarea,
} from "@fluentui/react-components";
import { api } from "../api/client";
import type { NodeDto, PrincipalRef } from "../api/types";
import { ErrorBar } from "../components/States";
import { PeoplePicker } from "../dialogs/PeoplePicker";
import type { Approval, ApprovalKind, ApprovalMode } from "./types";

const MODES: Record<ApprovalMode, string> = {
  ALL: "Everyone must approve",
  ANY: "The first answer decides",
  SEQUENTIAL: "One after another, in this order",
};
const REVIEW_MODES: Partial<Record<ApprovalMode, string>> = { ALL: "Everyone at once", SEQUENTIAL: "One after another, in this order" };

/** Asks people to approve or review a file or list item. */
export function RequestApprovalDialog({ node, kind, onClose }: { node: NodeDto; kind: ApprovalKind; onClose: () => void }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const review = kind === "REVIEW";
  const modes = review ? REVIEW_MODES : MODES;
  const [people, setPeople] = useState<PrincipalRef[]>([]);
  const [mode, setMode] = useState<ApprovalMode>("ALL");
  const [message, setMessage] = useState("");
  const [dueDays, setDueDays] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const send = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const a = await api<Approval>(`/api/v1/nodes/${node.id}/approvals`, {
        method: "POST",
        body: { kind, mode, message: message || undefined, people: people.map((p) => p.id), dueDays: dueDays ? Number(dueDays) : undefined },
      });
      await qc.invalidateQueries();
      navigate(`/approvals/${a.id}`);
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 520 }}>
        <form onSubmit={send}>
          <DialogBody>
            <DialogTitle>{review ? "Request a review of" : "Request approval of"} {node.title ?? node.name}</DialogTitle>
            <DialogContent style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <ErrorBar error={error} />
              <Field label={review ? "Reviewers" : "Approvers"} required>
                <PeoplePicker onSelect={(p) => p && setPeople((l) => (l.some((x) => x.id === p.id) ? l : [...l, p]))} />
              </Field>
              {people.length > 0 && (
                <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
                  {people.map((p) => (
                    <Button key={p.id} size="small" title="Remove" onClick={() => setPeople((l) => l.filter((x) => x.id !== p.id))}>{p.displayName} ×</Button>
                  ))}
                </div>
              )}
              <Field label="How it works">
                <Dropdown value={modes[mode]} selectedOptions={[mode]} onOptionSelect={(_, d) => setMode(d.optionValue as ApprovalMode)}>
                  {Object.entries(modes).map(([k, label]) => <Option key={k} value={k}>{label}</Option>)}
                </Dropdown>
              </Field>
              <Field label="Message (optional)">
                <Textarea value={message} onChange={(_, d) => setMessage(d.value)} maxLength={2000} />
              </Field>
              <Field label="Due in days (optional)">
                <Input type="number" min={1} max={365} value={dueDays} onChange={(_, d) => setDueDays(d.value)} />
              </Field>
            </DialogContent>
            <DialogActions>
              <Button onClick={onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={busy || !people.length}>Send request</Button>
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}
