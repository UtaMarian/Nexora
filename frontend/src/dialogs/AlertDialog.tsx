import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button, Dialog, DialogActions, DialogBody, DialogContent, DialogSurface, DialogTitle, Radio, RadioGroup, Text,
} from "@fluentui/react-components";
import { api } from "../api/client";
import type { AlertDto, AlertFrequency, NodeDto } from "../api/types";
import { ErrorBar } from "../components/States";

export const FREQUENCIES: Record<AlertFrequency, string> = {
  IMMEDIATE: "Right away", DAILY: "Daily summary", WEEKLY: "Weekly summary",
};

/** "Alert me" for a library, list, folder, file or item. */
export function AlertDialog({ node, onClose }: { node: NodeDto; onClose: () => void }) {
  const qc = useQueryClient();
  const current = useQuery({ queryKey: ["alert", node.id], queryFn: () => api<AlertDto | undefined>(`/api/v1/nodes/${node.id}/alert`) });
  const [frequency, setFrequency] = useState<AlertFrequency>("IMMEDIATE");
  const [error, setError] = useState<unknown>(null);
  const alert = current.data;

  useEffect(() => {
    if (alert) setFrequency(alert.frequency);
  }, [alert]);

  const run = async (fn: () => Promise<unknown>) => {
    try {
      await fn();
      await qc.invalidateQueries({ queryKey: ["alert", node.id] });
      await qc.invalidateQueries({ queryKey: ["my-alerts"] });
      onClose();
    } catch (e) {
      setError(e);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 420 }}>
        <DialogBody>
          <DialogTitle>Alert me about {node.title ?? node.name}</DialogTitle>
          <DialogContent style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            <ErrorBar error={error ?? current.error} />
            <Text>We email you when someone changes this{node.kind === "FILE" || node.kind === "LIST_ITEM" ? "" : " or anything in it"}.</Text>
            <RadioGroup value={frequency} onChange={(_, d) => setFrequency(d.value as AlertFrequency)} aria-label="How often">
              {Object.entries(FREQUENCIES).map(([value, label]) => <Radio key={value} value={value} label={label} />)}
            </RadioGroup>
          </DialogContent>
          <DialogActions>
            {alert && <Button onClick={() => run(() => api(`/api/v1/me/alerts/${alert.id}`, { method: "DELETE" }))}>Stop alerts</Button>}
            <Button onClick={onClose}>Cancel</Button>
            <Button appearance="primary" onClick={() => run(() => api(`/api/v1/nodes/${node.id}/alert`, { method: "PUT", body: { frequency } }))}>
              {alert ? "Save" : "Alert me"}
            </Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}
